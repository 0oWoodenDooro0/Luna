import os
import re

def get_kt_files(directory):
    files = []
    for root, _, filenames in os.walk(directory):
        for filename in filenames:
            if filename.endswith(".kt"):
                files.append(os.path.join(root, filename))
    return files

src_dir = "src"
kt_files = get_kt_files(src_dir)

# Build class to package mapping
class_map = {}
for filepath in kt_files:
    # filepath example: src/main/kotlin/luna/core/Command.kt
    # package from path: luna.core
    parts = filepath.split(os.sep)
    kotlin_idx = parts.index("kotlin")
    pkg_parts = parts[kotlin_idx+1:-1]
    pkg_name = ".".join(pkg_parts)
    
    filename = parts[-1].replace(".kt", "")
    class_map[filename] = pkg_name

# Some additional classes that might be used and are defined within these files
additional_classes = {
    "PlayersTable": "luna.core.repository",
    "PlayerRepository": "luna.core.repository",
    "DatabaseManager": "luna.core.repository",
    "Command": "luna.core",
    "UndercoverManager": "luna.undercover",
    "RpgConfig": "luna.rpg",
    "CombatEngine": "luna.rpg",
    "Attributes": "luna.rpg",
    "Equipment": "luna.rpg",
    "Monster": "luna.rpg",
    "Player": "luna.rpg",
    "RewardGenerator": "luna.rpg",
    "DatabaseTest": "luna.core.repository",
    "EquipmentDatabaseTest": "luna.rpg",
    "EquipmentModelTest": "luna.rpg",
    "MonsterPersistenceTest": "luna.rpg",
    "PlayerRepositoryProgressionTest": "luna.rpg",
    "RecoveryDatabaseTest": "luna.rpg",
    "RecoveryLogicTest": "luna.rpg",
    "RewardLogicTest": "luna.rpg",
    "RpgConfigRefactorTest": "luna.rpg",
    "RpgConfigTest": "luna.rpg",
    "RpgCoreTest": "luna.rpg",
    "UpgradeLogicTest": "luna.rpg"
}
class_map.update(additional_classes)

def replace_imports(content):
    lines = content.split('\n')
    new_lines = []
    for line in lines:
        if line.startswith('package '):
            # Handled separately
            continue
        elif line.startswith('import website.woodendoor.'):
            # Parse the class being imported
            imported = line.split('import ')[1].strip()
            # imported might be website.woodendoor.rpg.RpgConfig
            class_name = imported.split('.')[-1]
            if class_name in class_map:
                new_lines.append(f"import {class_map[class_name]}.{class_name}")
            elif class_name == "*":
                # Handle star imports if any.
                old_pkg = imported.replace(".*", "")
                if old_pkg == "website.woodendoor.repository":
                    new_lines.append(f"import luna.core.repository.*")
                elif old_pkg == "website.woodendoor.rpg":
                    new_lines.append(f"import luna.rpg.*")
                elif old_pkg == "website.woodendoor.command":
                    new_lines.append(f"import luna.undercover.command.*")
                elif old_pkg == "website.woodendoor":
                    new_lines.append(f"import luna.core.*")
                else:
                    new_lines.append(line)
            else:
                new_lines.append(line)
        else:
            new_lines.append(line)
    return '\n'.join(new_lines)

for filepath in kt_files:
    parts = filepath.split(os.sep)
    kotlin_idx = parts.index("kotlin")
    pkg_parts = parts[kotlin_idx+1:-1]
    pkg_name = ".".join(pkg_parts)
    
    with open(filepath, 'r') as f:
        content = f.read()
        
    # Replace package
    content = re.sub(r'^package\s+.*$', f'package {pkg_name}', content, flags=re.MULTILINE)
    
    # Replace imports
    content = replace_imports(content)
    
    # Also replace fully qualified inline usages if any
    content = content.replace("website.woodendoor.repository.", "luna.core.repository.")
    content = content.replace("website.woodendoor.rpg.", "luna.rpg.")
    content = content.replace("website.woodendoor.command.", "luna.undercover.command.")
    content = content.replace("website.woodendoor.", "luna.core.") # careful with order, but this is a naive replace
    
    with open(filepath, 'w') as f:
        f.write(content)

print("Files updated successfully.")
