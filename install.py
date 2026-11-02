#!/usr/bin/env python3
"""Install Toyota bZ3X HU ADB through the repository device tools."""
import argparse
import subprocess
from pathlib import Path

PACKAGE = "org.gc.bz3x.huadb"

def main():
    here = Path(__file__).resolve().parent
    root = here
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, default=here / "out/hu-wifi-adb-v1.apk")
    parser.add_argument("--grant-settings", action="store_true", help="Explicitly grant WRITE_SECURE_SETTINGS through tools/device/app-permissions")
    parser.add_argument("--grant-localization", action="store_true", help="Grant CHANGE_CONFIGURATION and allow WRITE_SETTINGS for central-screen localization")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    install = [str(root / "tools/device/install-apk"), str(args.apk.resolve()), "--serial", args.serial, "--json"]
    if args.dry_run: install.append("--dry-run")
    subprocess.run(install, check=True)
    permissions_to_grant = []
    if args.grant_settings:
        permissions_to_grant.append("android.permission.WRITE_SECURE_SETTINGS")
    if args.grant_localization:
        permissions_to_grant.append("android.permission.CHANGE_CONFIGURATION")
    if permissions_to_grant and not args.dry_run:
        permissions = str(root / "tools/device/app-permissions")
        common = ["--serial", args.serial, "--json"]
        subprocess.run([permissions, "inspect", PACKAGE] + common, check=True)
        subprocess.run([permissions, "grant", PACKAGE] + permissions_to_grant + common, check=True)
        subprocess.run([permissions, "inspect", PACKAGE] + common, check=True)
        if args.grant_localization:
            subprocess.run([permissions, "appops-set", PACKAGE, "--op", "WRITE_SETTINGS", "--mode", "allow"] + common, check=True)
            subprocess.run([permissions, "appops-get", PACKAGE, "--op", "WRITE_SETTINGS"] + common, check=True)

if __name__ == "__main__":
    main()
