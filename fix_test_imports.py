import os
import re

def get_kt_files(directory):
    files = []
    for root, _, filenames in os.walk(directory):
        for filename in filenames:
            if filename.endswith(".kt"):
                files.append(os.path.join(root, filename))
    return files

kt_files = get_kt_files("src/test/kotlin")

for filepath in kt_files:
    with open(filepath, 'r') as f:
        content = f.read()
        
    needs_players_table = "PlayersTable" in content and "import luna.core.repository.PlayersTable" not in content
    needs_player_repo = "PlayerRepository" in content and "import luna.core.repository.PlayerRepository" not in content
    
    if needs_players_table or needs_player_repo:
        lines = content.split('\n')
        # find the last import or package line
        insert_idx = 0
        for i, line in enumerate(lines):
            if line.startswith('import '):
                insert_idx = i
        
        if insert_idx == 0:
            for i, line in enumerate(lines):
                if line.startswith('package '):
                    insert_idx = i + 1
                    break
        
        new_lines = []
        if needs_players_table:
            new_lines.append("import luna.core.repository.PlayersTable")
        if needs_player_repo:
            new_lines.append("import luna.core.repository.PlayerRepository")
            
        lines.insert(insert_idx + 1, '\n'.join(new_lines))
        content = '\n'.join(lines)
        
        with open(filepath, 'w') as f:
            f.write(content)

print("Test imports added.")
