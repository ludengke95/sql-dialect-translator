#!/usr/bin/env python3
"""PG package reorganization migration."""
import os
import shutil

BASE = r"F:\java_workspace\sql-dialect-translator"
PG_SRC = os.path.join(BASE, "sdtp-protocol-pg/src/main/java/com/translator/proxy/protocol/pg")

def read_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

def delete_source(src):
    if os.path.exists(src):
        os.remove(src)

# Create sub-packages
for sub_dir in ['codec', 'auth', 'command', 'result', 'catalog']:
    os.makedirs(os.path.join(PG_SRC, sub_dir), exist_ok=True)

# Mapping: old filename → (subpkg, new filename if different from old)
pg_moves = {
    # D1: codec
    'PgWire.java': ('codec', None),
    'PgMessage.java': ('codec', None),
    'PgRawMessage.java': ('codec', None),
    'PgPacketDecoder.java': ('codec', None),
    'PgPacketEncoder.java': ('codec', None),
    # D2: auth
    'PgAuth.java': ('auth', None),
    'PgHandshakeHandler.java': ('auth', None),
    # D3: command (with class rename)
    'PgCommandDispatcher.java': ('command', 'PgCommandHandler.java'),
    # D4: result
    'PgResponseWriter.java': ('result', None),
    'PgTypeMapper.java': ('result', None),
    'PgOid.java': ('result', None),
    # D5: catalog
    'PgSystemCatalogProvider.java': ('catalog', None),
}

for fname, (subpkg, new_fname) in pg_moves.items():
    src = os.path.join(PG_SRC, fname)
    if not os.path.exists(src):
        print(f'  WARNING: Source not found: {src}')
        continue
    
    dst_fname = new_fname if new_fname else fname
    dst = os.path.join(PG_SRC, subpkg, dst_fname)
    
    content = read_file(src)
    
    # Change package declaration
    old_pkg = 'package com.translator.proxy.protocol.pg;'
    new_pkg = f'package com.translator.proxy.protocol.pg.{subpkg};'
    content = content.replace(old_pkg, new_pkg)
    
    # Fix class name for PgCommandDispatcher → PgCommandHandler
    if fname == 'PgCommandDispatcher.java':
        content = content.replace('class PgCommandDispatcher', 'class PgCommandHandler')
        content = content.replace('PgCommandDispatcher.get', 'PgCommandHandler.get')
        content = content.replace('new PgCommandDispatcher', 'new PgCommandHandler')
        content = content.replace('LoggerFactory.getLogger(PgCommandDispatcher.class)', 'LoggerFactory.getLogger(PgCommandHandler.class)')
    
    # Fix internal PG cross-references (update imports for all PG classes now in sub-packages)
    for other_fname, (other_subpkg, _) in pg_moves.items():
        old_import = f'com.translator.proxy.protocol.pg.{other_fname.replace(".java", "")}'
        new_import = f'com.translator.proxy.protocol.pg.{other_subpkg}.{other_fname.replace(".java", "")}'
        content = content.replace(f'import {old_import};', f'import {new_import};')
        # Also handle static method calls that may use class name directly
        # PgWire, PgAuth, PgOid are typically referenced by name (import needed)
    
    # Fix PgCommandDispatcher → PgCommandHandler in content
    if 'PgCommandDispatcher' in content:
        content = content.replace('PgCommandDispatcher', 'PgCommandHandler')
    
    # Fix frontend imports (core.frontend → protocol.frontend)
    content = content.replace('import com.translator.proxy.core.frontend.', 'import com.translator.proxy.protocol.frontend.')
    
    write_file(dst, content)
    delete_source(src)
    print(f'  Migrated: {fname} → pg/{subpkg}/{dst_fname}')

# PostgreSQLFrontendProtocol stays at top level but needs import fixes
pg_frontend = os.path.join(PG_SRC, 'PostgreSQLFrontendProtocol.java')
content = read_file(pg_frontend)
content = content.replace('import com.translator.proxy.core.frontend.', 'import com.translator.proxy.protocol.frontend.')
# Add imports for new sub-package classes
for fname, (subpkg, _) in pg_moves.items():
    class_name = fname.replace('.java', '')
    old_import_line = f'import com.translator.proxy.protocol.pg.{class_name};\n'
    if old_import_line in content:
        content = content.replace(old_import_line, '')
    # Also try without trailing newline
    if f'import com.translator.proxy.protocol.pg.{class_name};' in content:
        # Keep them, they're in the same top package so they work. 
        # Actually no - classes are not in same package anymore. Need to add sub-package imports.
        pass

# For PostgreSQLFrontendProtocol, fix class references that now need full imports.
# PgPacketDecoder, PgPacketEncoder, PgHandshakeHandler, PgCommandHandler, 
# PgResponseWriter, PgTypeMapper, PgSystemCatalogProvider

# The file already has import statements for these classes in the old pg package
# We need to update these imports to use the new sub-packages
content = content.replace('import com.translator.proxy.protocol.pg.PgPacketDecoder;', 'import com.translator.proxy.protocol.pg.codec.PgPacketDecoder;')
content = content.replace('import com.translator.proxy.protocol.pg.PgPacketEncoder;', 'import com.translator.proxy.protocol.pg.codec.PgPacketEncoder;')
content = content.replace('import com.translator.proxy.protocol.pg.PgHandshakeHandler;', 'import com.translator.proxy.protocol.pg.auth.PgHandshakeHandler;')
content = content.replace('import com.translator.proxy.protocol.pg.PgCommandDispatcher;', '')
content = content.replace('new PgCommandDispatcher()', 'new com.translator.proxy.protocol.pg.command.PgCommandHandler()')
content = content.replace('import com.translator.proxy.protocol.pg.PgResponseWriter;', 'import com.translator.proxy.protocol.pg.result.PgResponseWriter;')
content = content.replace('import com.translator.proxy.protocol.pg.PgTypeMapper;', 'import com.translator.proxy.protocol.pg.result.PgTypeMapper;')
content = content.replace('import com.translator.proxy.protocol.pg.PgSystemCatalogProvider;', 'import com.translator.proxy.protocol.pg.catalog.PgSystemCatalogProvider;')

write_file(pg_frontend, content)
print(f'  Updated imports: PostgreSQLFrontendProtocol.java')

# Update PG SPI service file
spi_dir = os.path.join(BASE, 'sdtp-protocol-pg/src/main/resources/META-INF/services')
old_spi = os.path.join(spi_dir, 'com.translator.proxy.core.frontend.FrontendProtocol')
new_spi = os.path.join(spi_dir, 'com.translator.proxy.protocol.frontend.FrontendProtocol')
if os.path.exists(old_spi):
    spi_content = read_file(old_spi)
    write_file(new_spi, spi_content)
    delete_source(old_spi)
    print(f'  Renamed SPI file: FrontendProtocol service')

print('\n=== PG migration completed ===')
