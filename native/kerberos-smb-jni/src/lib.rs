use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::{Component, Path, PathBuf};
use std::time::Duration;

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use smb2::client::{Cipher, Connection};
use smb2::{KerberosCredentials, Session, Tree};

struct SmbRequest {
    /// Logical Windows server name used in the `cifs/<server>` Kerberos SPN.
    server: String,
    /// DNS endpoint used only for the TCP connection.
    connect_host: String,
    share: String,
    username: String,
    password: String,
    realm: String,
    kdc_address: String,
    require_signing: bool,
    connect_timeout: Duration,
    response_timeout: Duration,
}

struct ReadRequest {
    smb: SmbRequest,
    path: String,
    max_bytes: usize,
}

struct DownloadRequest {
    smb: SmbRequest,
    path: String,
    destination: PathBuf,
    max_bytes: u64,
    expected_size: Option<u64>,
}

async fn connect_tree_with_kerberos(request: &SmbRequest) -> Result<(Connection, Tree), String> {
    let address = format!("{}:445", request.connect_host);
    let mut connection =
        Connection::connect_named(&address, &request.server, request.connect_timeout)
            .await
            .map_err(|error| stage_error("SMB-Verbindung", error))?;

    tokio::time::timeout(request.response_timeout, connection.negotiate())
        .await
        .map_err(|_| timeout_error("SMB-Aushandlung", request.response_timeout))?
        .map_err(|error| stage_error("SMB-Aushandlung", error))?;

    let credentials = KerberosCredentials {
        username: request.username.clone(),
        password: request.password.clone(),
        realm: request.realm.clone(),
        kdc_address: request.kdc_address.clone(),
    };

    let session = tokio::time::timeout(
        request.response_timeout,
        Session::setup_kerberos(&mut connection, &credentials, &request.server),
    )
    .await
    .map_err(|_| timeout_error("Kerberos-Anmeldung", request.response_timeout))?
    .map_err(|error| stage_error("Kerberos-Anmeldung", error))?;

    if request.require_signing && !session.should_sign {
        return Err(
            "Kerberos-Anmeldung: Der SMB-Server hat keine Signierung aktiviert".to_string(),
        );
    }

    let tree = tokio::time::timeout(
        request.response_timeout,
        Tree::connect(&mut connection, &request.share),
    )
    .await
    .map_err(|_| timeout_error("SMB-Freigabe", request.response_timeout))?
    .map_err(|error| stage_error("SMB-Freigabe", error))?;

    if tree.encrypt_data && !connection.should_encrypt() {
        let encryption_key = session.encryption_key.as_ref().ok_or_else(|| {
            "SMB-Freigabe verlangt Verschlüsselung, aber es wurde kein Schlüssel ausgehandelt"
                .to_string()
        })?;
        let decryption_key = session.decryption_key.as_ref().ok_or_else(|| {
            "SMB-Freigabe verlangt Verschlüsselung, aber es wurde kein Schlüssel ausgehandelt"
                .to_string()
        })?;
        let cipher = connection
            .params()
            .and_then(|parameters| parameters.cipher)
            .unwrap_or(Cipher::Aes128Ccm);
        connection.activate_encryption(encryption_key.clone(), decryption_key.clone(), cipher);
    }

    Ok((connection, tree))
}

async fn read_file_with_kerberos(request: ReadRequest) -> Result<Vec<u8>, String> {
    let (mut connection, tree) = connect_tree_with_kerberos(&request.smb).await?;

    let operation = async {
        let info = tree
            .stat(&mut connection, &request.path)
            .await
            .map_err(|error| stage_error("SMB-Dateiinformation", error))?;
        if info.is_directory {
            return Err("Der angegebene SMB-Pfad ist ein Verzeichnis".to_string());
        }
        if info.size > request.max_bytes as u64 {
            return Err(format!(
                "SMB-Datei ist mit {} Bytes größer als das Limit von {} Bytes",
                info.size, request.max_bytes
            ));
        }

        let bytes = tree
            .read_file_pipelined(&mut connection, &request.path)
            .await
            .map_err(|error| stage_error("SMB-Datei lesen", error))?;
        if bytes.len() > request.max_bytes {
            return Err(format!(
                "SMB-Datei ist größer als das Limit von {} Bytes",
                request.max_bytes
            ));
        }
        Ok(bytes)
    };

    let result = tokio::time::timeout(request.smb.response_timeout, operation)
        .await
        .map_err(|_| timeout_error("SMB-Dateizugriff", request.smb.response_timeout))?;

    // Disconnect errors must not hide the actual read result.
    let _ = tokio::time::timeout(Duration::from_secs(5), tree.disconnect(&mut connection)).await;

    result
}

async fn download_file_with_kerberos(request: DownloadRequest) -> Result<u64, String> {
    let (mut connection, tree) = connect_tree_with_kerberos(&request.smb).await?;
    let result = download_to_part_file(&tree, &mut connection, &request).await;

    // Disconnect errors must not hide the actual download result.
    let _ = tokio::time::timeout(Duration::from_secs(5), tree.disconnect(&mut connection)).await;

    result
}

async fn download_to_part_file(
    tree: &Tree,
    connection: &mut Connection,
    request: &DownloadRequest,
) -> Result<u64, String> {
    let response_timeout = request.smb.response_timeout;
    let info = tokio::time::timeout(response_timeout, tree.stat(connection, &request.path))
        .await
        .map_err(|_| timeout_error("SMB-Dateiinformation", response_timeout))?
        .map_err(|error| stage_error("SMB-Dateiinformation", error))?;
    if info.is_directory {
        return Err("Der angegebene SMB-Pfad ist ein Verzeichnis".to_string());
    }
    validate_download_size(info.size, request)?;

    let mut download =
        tokio::time::timeout(response_timeout, tree.download(connection, &request.path))
            .await
            .map_err(|_| timeout_error("SMB-Datei öffnen", response_timeout))?
            .map_err(|error| stage_error("SMB-Datei öffnen", error))?;

    let remote_size = download.size();
    if let Err(error) = validate_download_size(remote_size, request) {
        drop(download);
        return Err(error);
    }

    let mut output = match OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&request.destination)
    {
        Ok(output) => output,
        Err(error) => {
            drop(download);
            return Err(stage_error("Lokale Download-Datei erstellen", error));
        }
    };

    let transfer_result: Result<u64, String> = async {
        let mut bytes_written = 0_u64;
        loop {
            let next = tokio::time::timeout(response_timeout, download.next_chunk())
                .await
                .map_err(|_| timeout_error("SMB-Datei herunterladen", response_timeout))?;
            let Some(chunk_result) = next else {
                break;
            };
            let chunk =
                chunk_result.map_err(|error| stage_error("SMB-Datei herunterladen", error))?;
            let new_size = bytes_written
                .checked_add(chunk.len() as u64)
                .ok_or_else(|| "SMB-Downloadgröße ist übergelaufen".to_string())?;
            if new_size > request.max_bytes {
                return Err(format!(
                    "SMB-Datei ist größer als das Limit von {} Bytes",
                    request.max_bytes
                ));
            }

            output
                .write_all(&chunk)
                .map_err(|error| stage_error("Lokale Download-Datei schreiben", error))?;
            bytes_written = new_size;
        }

        if bytes_written != remote_size {
            return Err(format!(
                "SMB-Download ist unvollständig: {} von {} Bytes empfangen",
                bytes_written, remote_size
            ));
        }
        if let Some(expected_size) = request.expected_size {
            if bytes_written != expected_size {
                return Err(format!(
                    "SMB-Downloadgröße stimmt nicht: erwartet {} Bytes, empfangen {} Bytes",
                    expected_size, bytes_written
                ));
            }
        }

        output
            .flush()
            .map_err(|error| stage_error("Lokale Download-Datei abschließen", error))?;
        Ok(bytes_written)
    }
    .await;

    // Release both the remote handle and local descriptor before attempting
    // cleanup. An incomplete FileDownload is closed by the subsequent tree
    // disconnect even when the transfer itself failed.
    drop(download);
    drop(output);

    match transfer_result {
        Ok(bytes_written) => Ok(bytes_written),
        Err(error) => Err(remove_partial_after_error(&request.destination, error)),
    }
}

fn validate_download_size(size: u64, request: &DownloadRequest) -> Result<(), String> {
    if size > request.max_bytes {
        return Err(format!(
            "SMB-Datei ist mit {} Bytes größer als das Limit von {} Bytes",
            size, request.max_bytes
        ));
    }
    if let Some(expected_size) = request.expected_size {
        if size != expected_size {
            return Err(format!(
                "SMB-Dateigröße stimmt nicht: erwartet {} Bytes, Server meldet {} Bytes",
                expected_size, size
            ));
        }
    }
    Ok(())
}

fn remove_partial_after_error(path: &Path, error: String) -> String {
    match fs::remove_file(path) {
        Ok(()) => error,
        Err(cleanup_error) if cleanup_error.kind() == ErrorKind::NotFound => error,
        Err(cleanup_error) => format!(
            "{error}; unvollständige lokale Datei konnte nicht entfernt werden: {cleanup_error}"
        ),
    }
}

fn stage_error(stage: &str, error: impl std::fmt::Display) -> String {
    format!("{stage}: {error}")
}

fn timeout_error(stage: &str, timeout: Duration) -> String {
    format!(
        "{stage}: Zeitüberschreitung nach {} ms",
        timeout.as_millis()
    )
}

fn get_string(
    environment: &mut JNIEnv<'_>,
    value: &JString<'_>,
    field_name: &str,
) -> Result<String, String> {
    environment
        .get_string(value)
        .map(String::from)
        .map_err(|error| format!("Ungültiger JNI-Parameter {field_name}: {error}"))
}

#[allow(clippy::too_many_arguments)]
fn parse_smb_request(
    environment: &mut JNIEnv<'_>,
    server: &JString<'_>,
    connect_host: &JString<'_>,
    share: &JString<'_>,
    username: &JString<'_>,
    password: &JString<'_>,
    realm: &JString<'_>,
    kdc_address: &JString<'_>,
    require_signing: jboolean,
    connect_timeout_millis: jint,
    response_timeout_millis: jint,
) -> Result<SmbRequest, String> {
    if connect_timeout_millis <= 0 || response_timeout_millis <= 0 {
        return Err("Zeitlimits müssen positiv sein".to_string());
    }

    Ok(SmbRequest {
        server: get_string(environment, server, "server")?,
        connect_host: get_string(environment, connect_host, "connectHost")?,
        share: get_string(environment, share, "share")?,
        username: get_string(environment, username, "username")?,
        password: get_string(environment, password, "password")?,
        realm: get_string(environment, realm, "realm")?,
        kdc_address: get_string(environment, kdc_address, "kdcAddress")?,
        require_signing: require_signing != 0,
        connect_timeout: Duration::from_millis(connect_timeout_millis as u64),
        response_timeout: Duration::from_millis(response_timeout_millis as u64),
    })
}

fn parse_destination_path(value: String) -> Result<PathBuf, String> {
    if value.contains('\0') {
        return Err("Download-Zielpfad enthält ein Nullzeichen".to_string());
    }
    let destination = PathBuf::from(value);
    if !destination.is_absolute() {
        return Err("Download-Zielpfad muss absolut sein".to_string());
    }
    if destination
        .components()
        .any(|component| matches!(component, Component::ParentDir | Component::CurDir))
    {
        return Err("Download-Zielpfad muss kanonisch sein".to_string());
    }
    let file_name = destination
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| "Download-Zielpfad hat keinen gültigen Dateinamen".to_string())?;
    if file_name.len() <= 5 || !file_name.to_ascii_lowercase().ends_with(".part") {
        return Err("Download-Zieldatei muss auf .part enden".to_string());
    }
    Ok(destination)
}

fn build_runtime() -> Result<tokio::runtime::Runtime, String> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("Native Netzwerk-Laufzeit konnte nicht gestartet werden: {error}"))
}

fn throw_io_exception(environment: &mut JNIEnv<'_>, message: String) {
    let sanitized = message
        .replace('\u{0000}', "")
        .chars()
        .take(4_096)
        .collect::<String>();
    let _ = environment.throw_new("java/io/IOException", sanitized);
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "system" fn Java_com_example_mde_NativeKerberosSmb_nativeReadFile(
    mut environment: JNIEnv<'_>,
    _instance: JObject<'_>,
    server: JString<'_>,
    connect_host: JString<'_>,
    share: JString<'_>,
    path: JString<'_>,
    username: JString<'_>,
    password: JString<'_>,
    realm: JString<'_>,
    kdc_address: JString<'_>,
    require_signing: jboolean,
    connect_timeout_millis: jint,
    response_timeout_millis: jint,
    max_bytes: jlong,
) -> jbyteArray {
    let request = (|| {
        let max_bytes = usize::try_from(max_bytes)
            .ok()
            .filter(|value| *value > 0 && *value <= i32::MAX as usize)
            .ok_or_else(|| "Ungültiges Dateigrößenlimit".to_string())?;

        Ok(ReadRequest {
            smb: parse_smb_request(
                &mut environment,
                &server,
                &connect_host,
                &share,
                &username,
                &password,
                &realm,
                &kdc_address,
                require_signing,
                connect_timeout_millis,
                response_timeout_millis,
            )?,
            path: get_string(&mut environment, &path, "path")?,
            max_bytes,
        })
    })();

    let request = match request {
        Ok(request) => request,
        Err(error) => {
            throw_io_exception(&mut environment, error);
            return std::ptr::null_mut();
        }
    };

    let runtime = match build_runtime() {
        Ok(runtime) => runtime,
        Err(error) => {
            throw_io_exception(&mut environment, error);
            return std::ptr::null_mut();
        }
    };

    match runtime.block_on(read_file_with_kerberos(request)) {
        Ok(bytes) => match environment.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(error) => {
                throw_io_exception(
                    &mut environment,
                    format!("Dateiinhalt konnte nicht an Android übergeben werden: {error}"),
                );
                std::ptr::null_mut()
            }
        },
        Err(error) => {
            throw_io_exception(&mut environment, error);
            std::ptr::null_mut()
        }
    }
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "system" fn Java_com_example_mde_NativeKerberosSmb_nativeDownloadFile(
    mut environment: JNIEnv<'_>,
    _instance: JObject<'_>,
    server: JString<'_>,
    connect_host: JString<'_>,
    share: JString<'_>,
    path: JString<'_>,
    username: JString<'_>,
    password: JString<'_>,
    realm: JString<'_>,
    kdc_address: JString<'_>,
    require_signing: jboolean,
    connect_timeout_millis: jint,
    response_timeout_millis: jint,
    destination_path: JString<'_>,
    max_bytes: jlong,
    expected_size_bytes: jlong,
) -> jlong {
    let request = (|| {
        let max_bytes = u64::try_from(max_bytes)
            .ok()
            .filter(|value| *value > 0)
            .ok_or_else(|| "Ungültiges Dateigrößenlimit".to_string())?;
        let expected_size = match expected_size_bytes {
            -1 => None,
            value if value >= 0 => Some(value as u64),
            _ => return Err("Ungültige erwartete Dateigröße".to_string()),
        };
        if expected_size.is_some_and(|value| value > max_bytes) {
            return Err("Erwartete Dateigröße überschreitet das Download-Limit".to_string());
        }

        let destination = parse_destination_path(get_string(
            &mut environment,
            &destination_path,
            "destinationPath",
        )?)?;

        Ok(DownloadRequest {
            smb: parse_smb_request(
                &mut environment,
                &server,
                &connect_host,
                &share,
                &username,
                &password,
                &realm,
                &kdc_address,
                require_signing,
                connect_timeout_millis,
                response_timeout_millis,
            )?,
            path: get_string(&mut environment, &path, "path")?,
            destination,
            max_bytes,
            expected_size,
        })
    })();

    let request = match request {
        Ok(request) => request,
        Err(error) => {
            throw_io_exception(&mut environment, error);
            return -1;
        }
    };

    let runtime = match build_runtime() {
        Ok(runtime) => runtime,
        Err(error) => {
            throw_io_exception(&mut environment, error);
            return -1;
        }
    };

    match runtime.block_on(download_file_with_kerberos(request)) {
        Ok(bytes_written) => match jlong::try_from(bytes_written) {
            Ok(value) => value,
            Err(_) => {
                throw_io_exception(
                    &mut environment,
                    "Heruntergeladene Dateigröße kann nicht an Android übergeben werden"
                        .to_string(),
                );
                -1
            }
        },
        Err(error) => {
            throw_io_exception(&mut environment, error);
            -1
        }
    }
}
