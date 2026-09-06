#!/usr/bin/env python3
"""Publish the distribution tree directly to the GitHub 'dist' branch."""

import shutil
import subprocess
import sys
from pathlib import Path

def main():
    repo_dir = Path(__file__).resolve().parents[1]
    source_dir = repo_dir / "distribution-build"
    if not source_dir.is_dir():
        print(f"Distribution directory does not exist: {source_dir}")
        sys.exit(1)

    print("Publishing distribution to GitHub 'dist' branch...")
    
    # Clean any leftover local .git folder in the distribution directory
    git_dir = source_dir / ".git"
    if git_dir.exists():
        try:
            shutil.rmtree(git_dir)
        except Exception as e:
            print(f"Warning: Could not remove existing .git directory: {e}")

    try:
        # Initialize temp git repository
        subprocess.run(["git", "init"], cwd=source_dir, check=True)
        
        # Connect to parent repository's remote URL
        remote_url = "https://github.com/evgeniy111222333/oasis.git"
        subprocess.run(["git", "remote", "add", "origin", remote_url], cwd=source_dir, check=True)
        
        # Create new orphan branch
        subprocess.run(["git", "checkout", "-b", "dist"], cwd=source_dir, check=True)
        
        # Set config to avoid errors
        subprocess.run(["git", "config", "user.name", "Eclipse Publisher"], cwd=source_dir, check=True)
        subprocess.run(["git", "config", "user.email", "publisher@eclipse-roleplay.online"], cwd=source_dir, check=True)
        
        # Add all files to stage (allowing ignored files if any, but since it's a new repo inside the subfolder, there are no gitignores unless copied)
        subprocess.run(["git", "add", "-A"], cwd=source_dir, check=True)
        
        # Commit files
        subprocess.run(["git", "commit", "-m", "Publish distribution build"], cwd=source_dir, check=True)
        
        # Force push to rewrite the dist branch history (keeping the repository size clean)
        subprocess.run(["git", "push", "-f", "origin", "dist"], cwd=source_dir, check=True)
        print("Successfully published distribution to GitHub 'dist' branch!")
    except subprocess.CalledProcessError as e:
        print(f"Git push failed: {e}")
        sys.exit(1)
    finally:
        # Clean up local .git folder so we don't commit it to the main repository
        if git_dir.exists():
            try:
                shutil.rmtree(git_dir)
            except Exception as e:
                print(f"Warning: Could not clean up .git directory: {e}")

if __name__ == "__main__":
    main()
