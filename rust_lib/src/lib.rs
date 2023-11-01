use std::ffi::{c_char, c_int, CStr};
use std::fs;
use std::io;
use std::path::Path;

pub const OK: c_int = 0;
pub const FILE_OPEN_ERROR: c_int = 1;
pub const CREATE_FILE_ERROR: c_int = 2;
pub const SET_PERMISSION_ERROR: c_int = 3;
pub const ZIP_ERROR: c_int = 4;
pub const INVALID_STRING_CONVERSION_ERROR: c_int = 5;

#[no_mangle]
pub extern "C" fn unzip(in_path: *mut c_char, out_path: *mut c_char) -> c_int {
    match unzip_impl(in_path, out_path) {
        Ok(_) => OK,
        Err(code) => code
    }
}

type ResultExitCode<T> = Result<T, c_int>;

fn unzip_impl(in_path: *mut c_char, out_path: *mut c_char) -> ResultExitCode<()> {
    let in_path = c_chars_to_path(in_path)?;
    let out_path = c_chars_to_path(out_path)?;
    let file = fs::File::open(in_path).map_err(|_| FILE_OPEN_ERROR)?;

    let mut archive = zip::ZipArchive::new(file).map_err(|_| ZIP_ERROR)?;

    for i in 0..archive.len() {
        let mut file = archive.by_index(i).map_err(|_| ZIP_ERROR)?;
        let out_path = match file.enclosed_name() {
            Some(path) => out_path.join(path).to_owned(),
            None => continue,
        };

        if (*file.name()).ends_with('/') {
            fs::create_dir_all(&out_path).map_err(|_| CREATE_FILE_ERROR)?;
        } else {
            if let Some(p) = out_path.parent() {
                if !p.exists() {
                    fs::create_dir_all(p).map_err(|_| CREATE_FILE_ERROR)?;
                }
            }
            let mut outfile = fs::File::create(&out_path).map_err(|_| CREATE_FILE_ERROR)?;
            io::copy(&mut file, &mut outfile).map_err(|_| CREATE_FILE_ERROR)?;
        }

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;

            if let Some(mode) = file.unix_mode() {
                let permissions = fs::Permissions::from_mode(mode);
                fs::set_permissions(&out_path, permissions).map_err(|_| SET_PERMISSION_ERROR)?;
            }
        }
    }
    Ok(())
}

fn c_chars_to_path<'a>(chars: *mut c_char) -> ResultExitCode<&'a Path> {
    let c_str = unsafe { CStr::from_ptr(chars) };
    let str = c_str.to_str().map_err(|_| INVALID_STRING_CONVERSION_ERROR)?;
    Ok(Path::new(str))
}
