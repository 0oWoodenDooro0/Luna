import os

def get_kt_files(directory):
    files = []
    for root, _, filenames in os.walk(directory):
        for filename in filenames:
            if filename.endswith(".kt"):
                files.append(os.path.join(root, filename))
    return files

src_dir = "src"
kt_files = get_kt_files(src_dir)

for filepath in kt_files:
    parts = filepath.split(os.sep)
    kotlin_idx = parts.index("kotlin")
    pkg_parts = parts[kotlin_idx+1:-1]
    pkg_name = ".".join(pkg_parts)
    
    with open(filepath, 'r') as f:
        content = f.read()
        
    # Since the previous script stripped it out, there is no package line now.
    # Let's prepend it.
    if not content.startswith(f"package {pkg_name}"):
        content = f"package {pkg_name}\n\n" + content.lstrip()
    
    with open(filepath, 'w') as f:
        f.write(content)

print("Packages prepended successfully.")
