#!/usr/bin/env python3
"""
Sync patches from folia-server/minecraft-patches/features/ to patches/server/
Creates NeoForge server patches 0026-0032 mirroring 0014-0020.
"""
import os

FEATURES_DIR = "/home/z/my-project/crelia-neoforge/folia-server/minecraft-patches/features"
SERVER_PATCHES_DIR = "/home/z/my-project/crelia-neoforge/patches/server"

# Mapping: features patch number -> server patch number
MAPPING = {
    14: 26,
    15: 27,
    16: 28,
    17: 29,
    18: 30,
    19: 31,
    20: 32,
}

# Read features patch and adapt to server patch format
def adapt_patch(features_num, server_num):
    features_name = [f for f in os.listdir(FEATURES_DIR) if f.startswith(f"{features_num:04d}-")][0]
    features_path = os.path.join(FEATURES_DIR, features_name)
    
    with open(features_path, 'r') as f:
        content = f.read()
    
    # Change the filename
    parts = features_name.split('-', 1)
    server_name = f"{server_num:04d}-{parts[1]}"
    server_path = os.path.join(SERVER_PATCHES_DIR, server_name)
    
    with open(server_path, 'w') as f:
        f.write(content)
    
    print(f"  {features_name} -> {server_name}")
    return server_path

def main():
    print("=== Syncing NeoForge patches to patches/server/ ===")
    for feat_num, srv_num in sorted(MAPPING.items()):
        adapt_patch(feat_num, srv_num)
    
    # List all server patches
    patches = sorted([f for f in os.listdir(SERVER_PATCHES_DIR) if f.endswith('.patch')])
    print(f"\nTotal patches in patches/server/: {len(patches)}")
    for p in patches:
        size = os.path.getsize(os.path.join(SERVER_PATCHES_DIR, p))
        marker = " (NeoForge)" if int(p[:4]) >= 20 else ""
        print(f"  {p} ({size} bytes){marker}")

if __name__ == "__main__":
    main()
