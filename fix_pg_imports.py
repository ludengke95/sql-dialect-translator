#!/usr/bin/env python3
"""Fix PG cross-sub-package imports for all PG files."""
import os
import re

BASE = r"F:\java_workspace\sql-dialect-translator"
PG_SRC = os.path.join(BASE, "sdtp-protocol-pg/src/main/java/com/translator/proxy/protocol/pg")

# Map of class name → new fully qualified name
pg_class_map = {
    'PgWire': 'com.translator.proxy.protocol.pg.codec.PgWire',
    'PgMessage': 'com.translator.proxy.protocol.pg.codec.PgMessage',
    'PgRawMessage': 'com.translator.proxy.protocol.pg.codec.PgRawMessage',
    'PgPacketDecoder': 'com.translator.proxy.protocol.pg.codec.PgPacketDecoder',
    'PgPacketEncoder': 'com.translator.proxy.protocol.pg.codec.PgPacketEncoder',
    'PgAuth': 'com.translator.proxy.protocol.pg.auth.PgAuth',
    'PgHandshakeHandler': 'com.translator.proxy.protocol.pg.auth.PgHandshakeHandler',
    'PgCommandHandler': 'com.translator.proxy.protocol.pg.command.PgCommandHandler',
    'PgCommandDispatcher': 'com.translator.proxy.protocol.pg.command.PgCommandHandler',
    'PgResponseWriter': 'com.translator.proxy.protocol.pg.result.PgResponseWriter',
    'PgTypeMapper': 'com.translator.proxy.protocol.pg.result.PgTypeMapper',
    'PgOid': 'com.translator.proxy.protocol.pg.result.PgOid',
    'PgSystemCatalogProvider': 'com.translator.proxy.protocol.pg.catalog.PgSystemCatalogProvider',
    'PostgreSQLFrontendProtocol': 'com.translator.proxy.protocol.pg.PostgreSQLFrontendProtocol',
}

def read_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_file(path, content):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

def get_package_from_path(filepath):
    # Determine which sub-package this file is in
    rel = os.path.relpath(filepath, PG_SRC)
    parts = rel.replace('\\', '/').split('/')
    if len(parts) > 2:
        return 'com.translator.proxy.protocol.pg.' + parts[0]
    return 'com.translator.proxy.protocol.pg'

def fix_file(filepath):
    content = read_file(filepath)
    current_pkg = get_package_from_path(filepath)
    
    # Find all PG class references in the file
    used_classes = set()
    for class_name, fqcn in pg_class_map.items():
        # Skip if this is the current class or if already imported
        # Check if the class is used in the code
        if class_name == os.path.basename(filepath).replace('.java', ''):
            continue
        
        # Simple check: is the class name mentioned in the code (not in import/packet statements)?
        if class_name in content:
            # Make sure it's not just in a comment
            # Check if there's already an import for it
            import_line = f'import {fqcn};'
            if import_line not in content:
                used_classes.add((class_name, fqcn))
    
    if not used_classes:
        return  # No changes needed
    
    # Find the last import line and add new imports
    lines = content.split('\n')
    new_lines = []
    imports_added = set()
    
    in_imports = True
    last_import_line = -1
    
    for i, line in enumerate(lines):
        if line.startswith('import '):
            last_import_line = i
        elif line.startswith('package ') or line.startswith('//'):
            pass  # skip
    
    # Add imports after the last import line
    for i, line in enumerate(lines):
        new_lines.append(line)
        if i == last_import_line:
            for class_name, fqcn in sorted(used_classes, key=lambda x: x[1]):
                new_lines.append(f'import {fqcn};')
                imports_added.add(class_name)
    
    if new_lines != lines:
        write_file(filepath, '\n'.join(new_lines))
        print(f'  Fixed imports in: {os.path.basename(filepath)}')
        for cn, _ in sorted(used_classes, key=lambda x: x[1]):
            print(f'    + import {cn}')

# Process all Java files in PG module
for root, dirs, files in os.walk(PG_SRC):
    for f in files:
        if f.endswith('.java'):
            filepath = os.path.join(root, f)
            fix_file(filepath)

print('\n=== PG import fixes completed ===')
