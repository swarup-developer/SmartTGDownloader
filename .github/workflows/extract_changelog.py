import sys
import re

def main():
    if len(sys.argv) < 2:
        print("Usage: python extract_changelog.py <version>")
        sys.exit(1)

    version = sys.argv[1]
    changelog_path = "CHANGELOG.txt"

    try:
        with open(changelog_path, "r", encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        print(f"Error: {changelog_path} not found.")
        sys.exit(1)

    # Split by the separator used in CHANGELOG.txt
    parts = content.split("========================================================================")
    release_body = ""

    for i in range(1, len(parts), 2):
        header = parts[i].strip()
        # Look for "Version: <version>" in the header
        # Since header might contain "Version: 1.0.2 / 8.7.1" or "Version: 1.0.1"
        if f"Version: {version}" in header:
            if i + 1 < len(parts):
                body = parts[i+1].strip()
                release_body = f"{header}\n\n{body}"
                break

    if not release_body:
        print(f"Warning: Could not find changelog section for version {version}")
        # Default fallback release description
        release_body = f"## SmartTG Downloader Android Release v{version}\n\nThis is an automated release of the Android client."

    with open("release_notes.txt", "w", encoding="utf-8") as f:
        f.write(release_body)
    
    print("Release notes written to release_notes.txt")

if __name__ == "__main__":
    main()
