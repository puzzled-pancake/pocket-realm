//Patches some QoL stuff into the old 1.12.1 WoW client.

//Files can be verified after patching with:
//cmp -l WoW_patched.exe WoW.exe | gawk '{printf "%08X %02X %02X\n", $1, strtonum(0$2), strtonum(0$3)}'

use std::fs;
use std::process::ExitCode;
use std::ffi::OsString;
use std::ffi::CString;
use clap::Parser;

// Command line arguments
#[derive(Parser, Debug)]
#[clap(author)]
#[clap(version)]
#[clap(about = "Applies patches to enhance the functionality of the 1.12.1 World of Warcraft client")]
#[clap(long_about = "Applies patches to enhance the functionality of the 1.12.1 World of Warcraft client.

The following patches are currently enabled by default:
- Widescreen FoV fix
- Sound in background patch
- Sound channel count increase
- Farclip (terrain render distance) maximum value change
- Frilldistance (grass render distance) change
- Quickloot by default patch (hold shift for manual loot)
- Nameplate range change
- Large address aware patch
- Camera rotation skip glitch fix

The following patches are disabled by default, and can be enabled with command line parameters:
- Maximum camera distance limit increase")]
struct Args {
    /// Path to WoW.exe.
    #[clap(value_parser)]
    infile: String,

    /// Filename of the output file.
    #[clap(short, default_value_t = String::from("WoW_tweaked.exe"), value_parser)]
    outfile: String,

    /// FoV value in radians. Default game value is 1.5708.
    #[clap(long, default_value_t = 1.925f32, value_parser)]
    fov: f32,

    /// Farclip (terrain render distance) maximum value. Default game value is 777. Set with `/console farclip 1000` in-game.
    #[clap(long, default_value_t = 10000f32, value_parser)]
    farclip: f32,

    /// Frilldistance (grass render distance) value. Default game value is 70.
    #[clap(long, default_value_t = 300f32, value_parser)]
    frilldistance: f32,

    /// Nameplate distance in yards. Default game value is 20.
    #[clap(long, default_value_t = 41f32, value_parser)]
    nameplatedistance: f32,

    /// Default sound channel count. This can also be set with /console SoundSoftwareChannels 64, but is included here so that the changes persist if Config.wtf is deleted.
    /// Default game value is 12. Default value in TBC is 32(?). Default value in modern client is 64. 999 is the maximum value settable here.
    /// If you experience problems with performance, try lowering this. Values above 64 may cause crashes.
    #[clap(long, default_value_t = 64i32, value_parser = clap::value_parser!(i32).range(1..999))]
    soundchannels: i32,

    /// Max camera distance LIMIT. Current max camera distance is a setting in the menu & a console command. Default game value is 50. Unchanged by default. Should be greater than 0, otherwise bad things may happen.
    /// After patching, change with /console CameraDistanceMax 100
    #[clap(long, value_parser)]
    maxcameradistance: Option<f32>,

    /// If set, do not patch FoV.
    #[clap(long, default_value_t = false, value_parser)]
    no_fov: bool,

    /// If set, do not patch farclip.
    #[clap(long, default_value_t = false, value_parser)]
    no_farclip: bool,

    /// If set, do not patch frilldistance.
    #[clap(long, default_value_t = false, value_parser)]
    no_frilldistance: bool,

    /// If set, do not patch sound in background.
    #[clap(long, default_value_t = false, value_parser)]
    no_sound_in_background: bool,

    /// If set, do not patch quickloot.
    #[clap(long, default_value_t = false, value_parser)]
    no_quickloot: bool,

    /// If set, do not patch nameplate distance.
    #[clap(long, default_value_t = false, value_parser)]
    no_nameplatedistance: bool,

    /// If set, do not patch the number of sound channels.
    #[clap(long, default_value_t = false, value_parser)]
    no_soundchannels: bool,

    /// If set, do not patch the executable to be Large Address Aware.
    /// You may want to enable this if playing on incredibly low-end hardware with less than 3 GiB RAM.
    #[clap(long, default_value_t = false, value_parser)]
    no_largeaddressaware: bool,

    /// If set, do not patch the fix for the camera sometimes skipping to a random direction when rotated.
    #[clap(long, default_value_t = false, value_parser)]
    no_cameraskipfix: bool
}

/// Highest byte offset any patch touches (frilldistance float at 0x467958 + 4).
/// (Verification round 1 caught the old floor missing the nameplate/farclip/
/// frilldistance offsets; patch_range bounds-checks every write regardless.)
const MAX_PATCHED_OFFSET_END: usize = 0x467958 + 4;

/// Validate the input is the expected PE32 i386 executable before any write.
/// Every fixed-offset patch silently corrupts anything else :
/// wrong-version exes, truncated files, and non-PE inputs must fail cleanly,
/// never panic and never write an output file.
fn validate_pe32_i386(file: &[u8]) -> Result<(), String> {
    if file.len() < 0x40 {
        return Err(format!("input is {} bytes; not a PE image (too small)", file.len()));
    }
    if &file[0..2] != b"MZ" {
        return Err("input is not an MZ/PE executable (missing DOS magic)".to_string());
    }
    let e_lfanew = u32::from_le_bytes(
        file[0x3c..0x40].try_into().expect("length checked above"),
    ) as usize;
    if e_lfanew + 6 > file.len() {
        return Err(format!("PE header offset {e_lfanew:#x} is outside the {}-byte input", file.len()));
    }
    if &file[e_lfanew..e_lfanew + 4] != b"PE\0\0" {
        return Err(format!("no PE signature at e_lfanew {e_lfanew:#x}; not a PE executable"));
    }
    let machine = u16::from_le_bytes(
        file[e_lfanew + 4..e_lfanew + 6].try_into().expect("length checked above"),
    );
    // 0x14c = IMAGE_FILE_MACHINE_I386: every patch offset below was derived
    // from the 32-bit 1.12.1 client.
    if machine != 0x14c {
        return Err(format!("PE machine type {machine:#x} is not i386 ({:#x}); the 1.12.1 32-bit client is required", 0x14c));
    }
    if file.len() < MAX_PATCHED_OFFSET_END {
        return Err(format!("input is {} bytes; the 1.12.1 client (build 5875) is larger — wrong or truncated file", file.len()));
    }
    Ok(())
}

/// Bounds-checked, already-patched-aware fixed-offset write. Panics were the
/// old failure mode ; this returns errors instead.
fn patch_range(file: &mut [u8], offset: usize, bytes: &[u8], label: &str) -> Result<(), String> {
    let end = offset.checked_add(bytes.len()).ok_or_else(|| format!("{label}: offset overflow"))?;
    if end > file.len() {
        return Err(format!("{label}: range {offset:#x}..{end:#x} is outside the {}-byte input", file.len()));
    }
    if file[offset..end] == *bytes {
        // Idempotent re-application: the bytes are already in place.
        return Ok(());
    }
    file[offset..end].copy_from_slice(bytes);
    Ok(())
}

fn main() -> ExitCode {
    let args = Args::parse();

    //Open input file
    let file_path = &args.infile;
    let mut file: std::vec::Vec<u8> = match fs::read(file_path) {
        Ok(file) => file,
        Err(err) => {
            println!("Unable to read file: {err}");
            return ExitCode::from(1);
        }
    };

    if let Err(err) = validate_pe32_i386(&file) {
        println!("Refusing to patch {file_path}: {err}");
        return ExitCode::from(2);
    }

    let outfile_path = OsString::from(&args.outfile);

    /*
     * PATCHES PATCHES PATCHES PATCHES
     */

    let mut failure: Option<String> = None;

    // Large address aware patch
    if !args.no_largeaddressaware {
        const CHARACTERISTICS_OFFSET: usize = 0x126;
        let mut characteristics = u16::from_le_bytes(file[CHARACTERISTICS_OFFSET..CHARACTERISTICS_OFFSET+2].try_into().expect("validated above"));
        characteristics = characteristics | 0x20; // https://docs.microsoft.com/en-us/windows/win32/debug/pe-format#characteristics
        let characteristics_bytes = characteristics.to_le_bytes();
        print!("Applying patch: make executable large address aware...");
        match patch_range(&mut file, CHARACTERISTICS_OFFSET, &characteristics_bytes, "large address aware") {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Farclip patch
    if !args.no_farclip {
        const FARCLIP_OFFSET: usize = 0x40FED8;
        let farclip_bytes: [u8; 4] = args.farclip.to_le_bytes();
        print!("Applying patch: increased farclip max value...");
        match patch_range(&mut file, FARCLIP_OFFSET, &farclip_bytes, "farclip") {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Widescreen FoV patch
    if !args.no_fov {
        const FOV_OFFSET: usize = 0x4089B4;
        let fov_bytes = args.fov.to_le_bytes();
        print!("Applying patch: widescreen FoV fix...");
        match patch_range(&mut file, FOV_OFFSET, &fov_bytes, "FoV") {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Frilldistance patch
    if !args.no_frilldistance {
        const FRILLDISTANCE_OFFSET: usize = 0x467958;
        let frilldistance_bytes = args.frilldistance.to_le_bytes();
        print!("Applying patch: frilldistance (grass distance) increase...");
        match patch_range(&mut file, FRILLDISTANCE_OFFSET, &frilldistance_bytes, "frilldistance") {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Sound in background patch
    if !args.no_sound_in_background {
        const SOUND_IN_BACKGROUND_OFFSET: usize = 0x3A4869;
        const SOUND_IN_BACKGROUND_BYTES: [u8; 1] = [0x27];
        print!("Applying patch: sound in background...");
        match patch_range(&mut file, SOUND_IN_BACKGROUND_OFFSET, &SOUND_IN_BACKGROUND_BYTES, "sound in background") {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Sound channels patch
    if !args.soundchannels_skip(&args) {
        const SOUNDCHANNEL_OFFSET: usize = 0x435d38;
        let soundchannel_string = args.soundchannels.to_string();
        print!("Applying patch: software sound channels default increase...");
        let cstring = match CString::new(soundchannel_string) {
            Ok(value) => value,
            Err(err) => {
                println!(" FAILED!");
                failure = Some(format!("sound channels: {err}"));
                return finish(failure, &outfile_path, file);
            }
        };
        let soundchannel_bytes = cstring.to_bytes_with_nul();
        if soundchannel_bytes.len() <= 4 {
            match patch_range(&mut file, SOUNDCHANNEL_OFFSET, soundchannel_bytes, "sound channels") {
                Ok(()) => println!(" Success!"),
                Err(err) => { println!(" FAILED!"); failure = Some(err); }
            }
        }
        else {
            println!(" FAILED!");
            println!("Sound channels value is too long.");
            return ExitCode::from(1);
        }
    }

    // Quickloot key reverse patch (hold shift to manual loot)
    if !args.no_quickloot {
        const QUICKLOOT_OFFSET: usize = 0x0C1ECF;
        const QUICKLOOT_BYTES: [u8; 1] = [0x75];
        const QUICKLOOT_OFFSET2: usize = 0x0C2B25;
        const QUICKLOOT_BYTES2: [u8; 1] = [0x75];
        print!("Applying patch: quickloot reverse...");
        match patch_range(&mut file, QUICKLOOT_OFFSET, &QUICKLOOT_BYTES, "quickloot")
            .and_then(|()| patch_range(&mut file, QUICKLOOT_OFFSET2, &QUICKLOOT_BYTES2, "quickloot 2"))
        {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Nameplate range change patch
    if !args.no_nameplatedistance {
        const NAMEPLATE_OFFSET: usize = 0x40c448;
        let nameplate_bytes: [u8; 4] = args.nameplatedistance.to_le_bytes();
        print!("Applying patch: nameplate range...");
        match patch_range(&mut file, NAMEPLATE_OFFSET, &nameplate_bytes, "nameplate range") {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Max camera distance patch
    if let Some(maxcameradistance) = args.maxcameradistance {
        const MAXCAMERADISTANCE_OFFSET: usize = 0x4089a4;
        let maxcamera_bytes: [u8; 4] = maxcameradistance.to_le_bytes();
        print!("Applying patch: max camera distance...");
        match patch_range(&mut file, MAXCAMERADISTANCE_OFFSET, &maxcamera_bytes, "max camera distance") {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    // Camera skip glitch fix.
    // Thanks to Bon on the Turtle WoW Discord for implementing this patch, and phamd for submitting the PR to include it.
    if !args.no_cameraskipfix {
        let patches: [(usize, Vec<u8>); 5] = [
            (0x02ccd0, vec![0x55, 0x8b, 0x05, 0x48, 0x4e, 0x88, 0x00, 0x8b, 0x0d, 0x44, 0x4e, 0x88, 0x00, 0xe9, 0x33, 0x90,
                            0x32, 0x00, 0x83, 0xc0, 0x32, 0x83, 0xc1, 0x32, 0x3b, 0x0d, 0xa8, 0xeb, 0xc4, 0x00, 0x7e, 0x03,
                            0x83, 0xe9, 0x01, 0x3b, 0x05, 0xac, 0xeb, 0xc4, 0x00, 0x7e, 0x03, 0x83, 0xe8, 0x01, 0x83, 0xe9,
                            0x32, 0x83, 0xe8, 0x32, 0x89, 0x05, 0x48, 0x4e, 0x88, 0x00, 0x89, 0x0d, 0x44, 0x4e, 0x88, 0x00,
                            0x5d, 0xeb, 0x0d]),
            (0x02d326, vec![0xe9, 0xb1, 0x8a, 0x32, 0x00]),
            (0x02d334, vec![0x8b, 0x35, 0x48, 0x4e, 0x88, 0x00]),
            (0x355d15, vec![                              0x83, 0xf8, 0x32, 0x7d, 0x03, 0x83, 0xc0, 0x01, 0x83, 0xf9, 0x32,
                            0x7d, 0x03, 0x83, 0xc1, 0x01, 0xe9, 0xb8, 0x6f, 0xcd, 0xff]),
            (0x355ddc, vec![                                                                        0x8d, 0x4d, 0xf0, 0x51,
                            0xff, 0x35, 0x00, 0x4e, 0x88, 0x00, 0xff, 0x15, 0x50, 0xf6, 0x7f, 0x00, 0x8b, 0x45, 0xf0, 0x8b,
                            0x15, 0x44, 0x4e, 0x88, 0x00, 0xe9, 0x35, 0x75, 0xcd, 0xff])
        ];

        print!("Applying patch: camera skip glitch fix...");
        let mut camera_result: Result<(), String> = Ok(());
        for (address, bytes) in patches.iter() {
            camera_result = patch_range(&mut file, *address, bytes, "camera skip fix");
            if camera_result.is_err() {
                break;
            }
        }
        match camera_result {
            Ok(()) => println!(" Success!"),
            Err(err) => { println!(" FAILED!"); failure = Some(err); }
        }
    }

    finish(failure, &outfile_path, file)
}

impl Args {
    /// Mirrors the flag style of the other patches for the sound-channels
    /// block (kept as a helper so the patch order above reads uniformly).
    fn soundchannels_skip(&self, _args: &Args) -> bool {
        _args.no_soundchannels
    }
}

fn finish(failure: Option<String>, outfile_path: &OsString, file: Vec<u8>) -> ExitCode {
    if let Some(err) = failure {
        // A failed patch must never leave an output file behind: a partially
        // patched executable is silent corruption .
        println!("{err}");
        println!("No output file was written.");
        return ExitCode::from(3);
    }
    match fs::write(outfile_path, file) {
        Err(err) => {
            println!("File writing failed: {err}");
            return ExitCode::from(1);
        },
        Ok(_) => println!("Wrote file {}", outfile_path.to_string_lossy())
    };
    ExitCode::from(0)
}

/**
 * Replaces the first occurrence of find with replace, mutating haystack.
 * Returns true if a replacement occurred, false if not.
 */
#[allow(dead_code)] //unused, but I want to keep this here in case it's necessary later, so shut up compiler
fn replace(haystack: &mut Vec<u8>, find: &Vec<u8>, replace: &Vec<u8>) -> bool {
    if haystack.len() < find.len() {
        return false;
    }

    if haystack.len() < replace.len() {
        return false;
    }

    let mut match_index: Option<usize> = None;
    for i in 0..haystack.len() - find.len() + 1 {
        if haystack[i..i+find.len()] == find[..] {
            match_index = Some(i);
        }
    }

    let match_index = match match_index {
        None => return false,
        Some(idx) => idx
    };

    haystack.splice(match_index..match_index+replace.len(), replace.iter().cloned());
    return true;
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Minimal structurally valid PE32 i386 image covering the highest
    /// patched offset: MZ, e_lfanew -> PE\0\0, machine 0x14c.
    fn synthetic_pe() -> Vec<u8> {
        let mut image = vec![0u8; MAX_PATCHED_OFFSET_END + 0x100];
        image[0..2].copy_from_slice(b"MZ");
        image[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        image[0x80..0x84].copy_from_slice(b"PE\0\0");
        image[0x84..0x86].copy_from_slice(&0x14cu16.to_le_bytes());
        image
    }

    #[test]
    fn replace_should_succeed() {
        let mut data: Vec::<u8> = vec![1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
        let find: Vec::<u8> = vec![3, 4, 5, 6];
        let repl: Vec::<u8> = vec![6, 5, 4, 3];
        let return_val = replace(&mut data, &find, &repl);
        assert_eq!(data, [1u8, 2, 6, 5, 4, 3, 7, 8, 9, 10]);
        assert!(return_val);
    }

    #[test]
    fn replace_should_fail() {
        let mut data: Vec::<u8> = vec![1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
        let find: Vec::<u8> = vec![6, 6, 6, 6];
        let repl: Vec::<u8> = vec![6, 5, 4, 3];
        let return_val = replace(&mut data, &find, &repl);
        assert!(!return_val);
    }

    #[test]
    fn replace_shouldnt_panic() {
        let mut data: Vec::<u8> = vec![1, 2];
        let find: Vec::<u8> = vec![3, 4, 5, 6];
        let repl: Vec::<u8> = vec![6, 5, 4, 3];
        let return_val = replace(&mut data, &find, &repl);
        assert!(!return_val);
    }

    #[test]
    fn validation_rejects_truncated() {
        assert!(validate_pe32_i386(&synthetic_pe()[..0x20]).is_err());
        assert!(validate_pe32_i386(&[]).is_err());
    }

    #[test]
    fn validation_rejects_non_pe() {
        let mut image = synthetic_pe();
        image[0..2].copy_from_slice(b"XX");
        assert!(validate_pe32_i386(&image).is_err());
    }

    #[test]
    fn validation_rejects_wrong_architecture() {
        let mut image = synthetic_pe();
        image[0x84..0x86].copy_from_slice(&0x8664u16.to_le_bytes()); // x86-64
        let err = validate_pe32_i386(&image).unwrap_err();
        assert!(err.contains("not i386"));
    }

    #[test]
    fn validation_rejects_missing_pe_signature() {
        let mut image = synthetic_pe();
        image[0x80..0x84].copy_from_slice(b"ZZ\0\0");
        assert!(validate_pe32_i386(&image).is_err());
    }

    #[test]
    fn validation_rejects_too_small_for_patches() {
        let mut image = vec![0u8; 0x1000];
        image[0..2].copy_from_slice(b"MZ");
        image[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        image[0x80..0x84].copy_from_slice(b"PE\0\0");
        image[0x84..0x86].copy_from_slice(&0x14cu16.to_le_bytes());
        assert!(validate_pe32_i386(&image).is_err());
    }

    #[test]
    fn validation_accepts_synthetic_client() {
        assert!(validate_pe32_i386(&synthetic_pe()).is_ok());
    }

    #[test]
    fn patch_range_is_idempotent_and_bounds_checked() {
        let mut image = synthetic_pe();
        patch_range(&mut image, 0x0C1ECF, &[0x75], "quickloot").unwrap();
        let first = image[0x0C1ECF];
        assert_eq!(first, 0x75);
        patch_range(&mut image, 0x0C1ECF, &[0x75], "quickloot").unwrap();
        assert_eq!(image[0x0C1ECF], 0x75);
        let near_end = image.len() - 1;
        assert!(patch_range(&mut image, near_end, &[0x01, 0x02], "overflow").is_err());
        assert!(patch_range(&mut image, usize::MAX - 1, &[0x01], "usize overflow").is_err());
    }
}
