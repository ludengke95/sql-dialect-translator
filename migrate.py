#!/usr/bin/env python3
"""Migration script for SDT Proxy architecture refactoring."""
import os
import shutil
import re

BASE = r"F:\java_workspace\sql-dialect-translator"

def read_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

def delete_file(path):
    if os.path.exists(path):
        os.remove(path)

def delete_dir(path):
    if os.path.exists(path):
        shutil.rmtree(path)

# ==================== B1: codec files ====================
def migrate_codec():
    files = ['MySQLPacketDecoder.java', 'MySQLPacketEncoder.java']
    for f in files:
        src = os.path.join(BASE, 'sdtp-protocol/src/main/java/com/translator/proxy/protocol/codec', f)
        dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/main/java/com/translator/proxy/protocol/mysql/codec', f)
        content = read_file(src)
        content = content.replace('package com.translator.proxy.protocol.codec;', 'package com.translator.proxy.protocol.mysql.codec;')
        write_file(dst, content)
        delete_file(src)
        print(f'  Migrated codec: {f}')

# ==================== B2: constant files ====================
def migrate_constants():
    files = ['CapabilityFlags.java', 'ColumnType.java', 'CommandType.java', 'ServerStatus.java']
    for f in files:
        src = os.path.join(BASE, 'sdtp-protocol/src/main/java/com/translator/proxy/protocol/constant', f)
        dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/main/java/com/translator/proxy/protocol/mysql/constant', f)
        content = read_file(src)
        content = content.replace('package com.translator.proxy.protocol.constant;', 'package com.translator.proxy.protocol.mysql.constant;')
        write_file(dst, content)
        delete_file(src)
        print(f'  Migrated constant: {f}')

# ==================== B3: util files ====================
def migrate_util():
    files = ['BufferUtils.java', 'MySQLAuth.java']
    for f in files:
        src = os.path.join(BASE, 'sdtp-protocol/src/main/java/com/translator/proxy/protocol/util', f)
        dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/main/java/com/translator/proxy/protocol/mysql/util', f)
        content = read_file(src)
        content = content.replace('package com.translator.proxy.protocol.util;', 'package com.translator.proxy.protocol.mysql.util;')
        write_file(dst, content)
        delete_file(src)
        print(f'  Migrated util: {f}')

# ==================== B4: MySQL handler files to sub-packages ====================
def migrate_mysql_handlers():
    # MySQLFrontendProtocol stays at top level
    src = os.path.join(BASE, 'sdtp-protocol/src/main/java/com/translator/proxy/protocol/mysql/MySQLFrontendProtocol.java')
    dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/main/java/com/translator/proxy/protocol/mysql/MySQLFrontendProtocol.java')
    content = read_file(src)
    content = content.replace('package com.translator.proxy.protocol.mysql;', 'package com.translator.proxy.protocol.mysql;')
    # Fix imports
    content = content.replace(
        'import com.translator.proxy.core.frontend.AuthConfig;',
        'import com.translator.proxy.protocol.frontend.AuthConfig;')
    content = content.replace(
        'import com.translator.proxy.core.frontend.FrontendProtocol;',
        'import com.translator.proxy.protocol.frontend.FrontendProtocol;')
    content = content.replace(
        'import com.translator.proxy.core.frontend.ResponseWriter;',
        'import com.translator.proxy.protocol.frontend.ResponseWriter;')
    content = content.replace(
        'import com.translator.proxy.core.frontend.SystemCatalogProvider;',
        'import com.translator.proxy.protocol.frontend.SystemCatalogProvider;')
    content = content.replace(
        'import com.translator.proxy.core.frontend.TypeMapper;',
        'import com.translator.proxy.protocol.frontend.TypeMapper;')
    content = content.replace(
        'import com.translator.proxy.protocol.codec.MySQLPacketDecoder;',
        'import com.translator.proxy.protocol.mysql.codec.MySQLPacketDecoder;')
    content = content.replace(
        'import com.translator.proxy.protocol.codec.MySQLPacketEncoder;',
        'import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;')
    write_file(dst, content)
    delete_file(src)
    print(f'  Migrated: MySQLFrontendProtocol.java')

    # MySQLHandshakeHandler → mysql/auth
    handler_moves = {
        'MySQLHandshakeHandler.java': 'auth',
        'MySQLAuthHandler.java': 'auth',
        'MySQLCommandHandler.java': 'command',
        'MySQLResponseWriter.java': 'result',
        'MySQLTypeMapper.java': 'result',
        'MySQLSystemCatalogProvider.java': 'catalog',
    }
    for fname, subpkg in handler_moves.items():
        src = os.path.join(BASE, f'sdtp-protocol/src/main/java/com/translator/proxy/protocol/mysql', fname)
        dst = os.path.join(BASE, f'sdtp-protocol-mysql/src/main/java/com/translator/proxy/protocol/mysql', subpkg, fname)
        content = read_file(src)
        content = content.replace(
            f'package com.translator.proxy.protocol.mysql;',
            f'package com.translator.proxy.protocol.mysql.{subpkg};')
        
        # Fix all imports - rule 1: frontend interface package name change
        content = content.replace('import com.translator.proxy.core.frontend.', 'import com.translator.proxy.protocol.frontend.')
        
        # Fix all imports - rule 2: codec/constant/util package changes
        content = content.replace('import com.translator.proxy.protocol.codec.MySQLPacketDecoder;', 'import com.translator.proxy.protocol.mysql.codec.MySQLPacketDecoder;')
        content = content.replace('import com.translator.proxy.protocol.codec.MySQLPacketEncoder;', 'import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;')
        content = content.replace('import com.translator.proxy.protocol.constant.CapabilityFlags;', 'import com.translator.proxy.protocol.mysql.constant.CapabilityFlags;')
        content = content.replace('import com.translator.proxy.protocol.constant.ColumnType;', 'import com.translator.proxy.protocol.mysql.constant.ColumnType;')
        content = content.replace('import com.translator.proxy.protocol.constant.CommandType;', 'import com.translator.proxy.protocol.mysql.constant.CommandType;')
        content = content.replace('import com.translator.proxy.protocol.constant.ServerStatus;', 'import com.translator.proxy.protocol.mysql.constant.ServerStatus;')
        content = content.replace('import com.translator.proxy.protocol.util.BufferUtils;', 'import com.translator.proxy.protocol.mysql.util.BufferUtils;')
        content = content.replace('import com.translator.proxy.protocol.util.MySQLAuth;', 'import com.translator.proxy.protocol.mysql.util.MySQLAuth;')
        
        # Fix rule 3: MySQL handler sub-package internal references  
        # Fully qualified references in code
        content = content.replace('com.translator.proxy.protocol.codec.MySQLPacketDecoder', 'com.translator.proxy.protocol.mysql.codec.MySQLPacketDecoder')
        content = content.replace('com.translator.proxy.protocol.codec.MySQLPacketEncoder', 'com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder')
        content = content.replace('com.translator.proxy.protocol.constant.ServerStatus', 'com.translator.proxy.protocol.mysql.constant.ServerStatus')
        content = content.replace('com.translator.proxy.protocol.util.BufferUtils', 'com.translator.proxy.protocol.mysql.util.BufferUtils')
        
        # Fix import to MySQLAuthHandler from MySQLCommandHandler (now in command subpkg, references auth subpkg)
        content = content.replace(
            'import com.translator.proxy.protocol.mysql.MySQLAuthHandler;',
            'import com.translator.proxy.protocol.mysql.auth.MySQLAuthHandler;')
        content = content.replace(
            'import com.translator.proxy.protocol.mysql.MySQLSystemCatalogProvider;',
            'import com.translator.proxy.protocol.mysql.catalog.MySQLSystemCatalogProvider;')
        content = content.replace(
            'import com.translator.proxy.protocol.mysql.MySQLResponseWriter;',
            'import com.translator.proxy.protocol.mysql.result.MySQLResponseWriter;')
        content = content.replace(
            'import com.translator.proxy.protocol.mysql.MySQLTypeMapper;',
            'import com.translator.proxy.protocol.mysql.result.MySQLTypeMapper;')
        content = content.replace(
            'import com.translator.proxy.protocol.mysql.MySQLCommandHandler;',
            'import com.translator.proxy.protocol.mysql.command.MySQLCommandHandler;')
        content = content.replace(
            'import com.translator.proxy.protocol.mysql.MySQLHandshakeHandler;',
            'import com.translator.proxy.protocol.mysql.auth.MySQLHandshakeHandler;')
        
        # Fix intercept import
        content = content.replace(
            'import com.translator.proxy.core.intercept.SystemVariableInterceptor;',
            'import com.translator.proxy.protocol.mysql.util.SystemVariableInterceptor;')
        
        write_file(dst, content)
        delete_file(src)
        print(f'  Migrated handler: {fname} → mysql/{subpkg}')

# ==================== B5: SPI service file ====================
def migrate_spi():
    src = os.path.join(BASE, 'sdtp-protocol/src/main/resources/META-INF/services/com.translator.proxy.core.frontend.FrontendProtocol')
    dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/main/resources/META-INF/services/com.translator.proxy.protocol.frontend.FrontendProtocol')
    content = read_file(src)
    # Update the implementation class name
    content = content.replace('com.translator.proxy.protocol.mysql.MySQLFrontendProtocol', 'com.translator.proxy.protocol.mysql.MySQLFrontendProtocol')
    write_file(dst, content)
    delete_file(src)
    print(f'  Migrated SPI service file')

# ==================== B6: Test files ====================
def migrate_tests():
    # MySQLPacketCodecTest
    src = os.path.join(BASE, 'sdtp-protocol/src/test/java/com/translator/proxy/protocol/codec/MySQLPacketCodecTest.java')
    dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/test/java/com/translator/proxy/protocol/mysql/codec/MySQLPacketCodecTest.java')
    content = read_file(src)
    content = content.replace('package com.translator.proxy.protocol.codec;', 'package com.translator.proxy.protocol.mysql.codec;')
    # Fix imports in test
    content = content.replace('import com.translator.proxy.protocol.codec.', 'import com.translator.proxy.protocol.mysql.codec.')
    content = content.replace('import com.translator.proxy.protocol.constant.', 'import com.translator.proxy.protocol.mysql.constant.')
    content = content.replace('import com.translator.proxy.protocol.util.', 'import com.translator.proxy.protocol.mysql.util.')
    content = content.replace('com.translator.proxy.protocol.codec.', 'com.translator.proxy.protocol.mysql.codec.')
    content = content.replace('com.translator.proxy.protocol.constant.', 'com.translator.proxy.protocol.mysql.constant.')
    content = content.replace('com.translator.proxy.protocol.util.', 'com.translator.proxy.protocol.mysql.util.')
    write_file(dst, content)
    delete_file(src)
    print(f'  Migrated test: MySQLPacketCodecTest.java')

    # MySQLAuthTest
    src = os.path.join(BASE, 'sdtp-protocol/src/test/java/com/translator/proxy/protocol/util/MySQLAuthTest.java')
    dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/test/java/com/translator/proxy/protocol/mysql/util/MySQLAuthTest.java')
    content = read_file(src)
    content = content.replace('package com.translator.proxy.protocol.util;', 'package com.translator.proxy.protocol.mysql.util;')
    content = content.replace('import com.translator.proxy.protocol.util.', 'import com.translator.proxy.protocol.mysql.util.')
    content = content.replace('com.translator.proxy.protocol.util.', 'com.translator.proxy.protocol.mysql.util.')
    write_file(dst, content)
    delete_file(src)
    print(f'  Migrated test: MySQLAuthTest.java')

# ==================== C: Delete legacy ====================
def delete_legacy():
    delete_dir(os.path.join(BASE, 'sdtp-protocol/src/main/java/com/translator/proxy/protocol/legacy'))
    # Delete legacy test files
    delete_file(os.path.join(BASE, 'sdtp-protocol/src/test/java/com/translator/proxy/protocol/legacy/CommandHandlerTest.java'))
    delete_file(os.path.join(BASE, 'sdtp-protocol/src/test/java/com/translator/proxy/protocol/legacy/HandshakeAuthTest.java'))
    # Remove empty directories
    for d in [
        os.path.join(BASE, 'sdtp-protocol/src/test/java/com/translator/proxy/protocol/legacy'),
        os.path.join(BASE, 'sdtp-protocol/src/test/java/com/translator/proxy/protocol/codec'),
        os.path.join(BASE, 'sdtp-protocol/src/test/java/com/translator/proxy/protocol/util'),
    ]:
        if os.path.exists(d):
            try:
                os.rmdir(d)
            except OSError:
                pass
    print(f'  Deleted legacy package and test files')

# ==================== D: SystemVariableInterceptor ====================
def migrate_system_var_interceptor():
    src = os.path.join(BASE, 'sdtp-core/src/main/java/com/translator/proxy/core/intercept/SystemVariableInterceptor.java')
    dst = os.path.join(BASE, 'sdtp-protocol-mysql/src/main/java/com/translator/proxy/protocol/mysql/util/SystemVariableInterceptor.java')
    content = read_file(src)
    content = content.replace('package com.translator.proxy.core.intercept;', 'package com.translator.proxy.protocol.mysql.util;')
    # Remove @Deprecated annotation
    content = content.replace('@Deprecated\npublic final class', 'public final class')
    write_file(dst, content)
    delete_file(src)
    print(f'  Migrated SystemVariableInterceptor to sdtp-protocol-mysql/util')
    # Remove empty intercept directory
    try:
        os.rmdir(os.path.join(BASE, 'sdtp-core/src/main/java/com/translator/proxy/core/intercept'))
    except OSError:
        pass

# ==================== Update Backend/Server imports ====================
def fix_backend_imports():
    # TranslationQueryProcessor.java
    filepath = os.path.join(BASE, 'sdtp-backend/src/main/java/com/translator/proxy/backend/TranslationQueryProcessor.java')
    content = read_file(filepath)
    # Fix frontend import
    content = content.replace('import com.translator.proxy.core.frontend.FrontendProtocol;', 'import com.translator.proxy.protocol.frontend.FrontendProtocol;')
    # Remove legacy CommandHandler import
    content = content.replace('import com.translator.proxy.protocol.legacy.CommandHandler;\n', '')
    # Remove SystemVariableInterceptor import (moved to sdtp-protocol-mysql)
    content = content.replace('import com.translator.proxy.core.intercept.SystemVariableInterceptor;\n', '')
    write_file(filepath, content)
    print(f'  Fixed imports: TranslationQueryProcessor.java')

    # ResultSetEncoder.java
    filepath = os.path.join(BASE, 'sdtp-backend/src/main/java/com/translator/proxy/backend/mapper/ResultSetEncoder.java')
    content = read_file(filepath)
    content = content.replace('import com.translator.proxy.core.frontend.ResponseWriter;', 'import com.translator.proxy.protocol.frontend.ResponseWriter;')
    content = content.replace('import com.translator.proxy.core.frontend.TypeMapper;', 'import com.translator.proxy.protocol.frontend.TypeMapper;')
    content = content.replace('import com.translator.proxy.protocol.codec.MySQLPacketEncoder;', 'import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;')
    content = content.replace('import com.translator.proxy.protocol.constant.ServerStatus;', 'import com.translator.proxy.protocol.mysql.constant.ServerStatus;')
    content = content.replace('import com.translator.proxy.protocol.util.BufferUtils;', 'import com.translator.proxy.protocol.mysql.util.BufferUtils;')
    write_file(filepath, content)
    print(f'  Fixed imports: ResultSetEncoder.java')

    # TypeMapper.java (backend) - column type from sdtp-protocol.constant → sdtp-protocol-mysql.constant
    filepath = os.path.join(BASE, 'sdtp-backend/src/main/java/com/translator/proxy/backend/mapper/TypeMapper.java')
    content = read_file(filepath)
    content = content.replace('import com.translator.proxy.protocol.constant.ColumnType;', 'import com.translator.proxy.protocol.mysql.constant.ColumnType;')
    write_file(filepath, content)
    print(f'  Fixed imports: TypeMapper.java (backend)')

def fix_server_imports():
    # ProxyBootstrap.java
    filepath = os.path.join(BASE, 'sdtp-server/src/main/java/com/translator/proxy/server/ProxyBootstrap.java')
    content = read_file(filepath)
    content = content.replace('import com.translator.proxy.core.frontend.AuthConfig;', 'import com.translator.proxy.protocol.frontend.AuthConfig;')
    content = content.replace('import com.translator.proxy.core.frontend.FrontendProtocol;', 'import com.translator.proxy.protocol.frontend.FrontendProtocol;')
    content = content.replace('import com.translator.proxy.core.frontend.FrontendProtocols;', 'import com.translator.proxy.protocol.frontend.FrontendProtocols;')
    # Remove legacy CommandHandler import and reference
    content = content.replace('import com.translator.proxy.protocol.legacy.CommandHandler;\n', '')
    content = content.replace('import com.translator.proxy.protocol.mysql.MySQLCommandHandler;', 'import com.translator.proxy.protocol.mysql.command.MySQLCommandHandler;')
    # Remove call to legacy CommandHandler
    content = content.replace('        CommandHandler.setBackendRouter(backendPoolManager);\n', '')
    # Fix SystemVariableInterceptor reference
    content = content.replace('com.translator.proxy.core.intercept.SystemVariableInterceptor', 'com.translator.proxy.protocol.mysql.util.SystemVariableInterceptor')
    write_file(filepath, content)
    print(f'  Fixed imports: ProxyBootstrap.java')

# ==================== Cleanup empty sdtp-protocol directories ====================
def cleanup_sdtp_protocol():
    for d in [
        'sdtp-protocol/src/main/java/com/translator/proxy/protocol/codec',
        'sdtp-protocol/src/main/java/com/translator/proxy/protocol/constant',
        'sdtp-protocol/src/main/java/com/translator/proxy/protocol/util',
        'sdtp-protocol/src/main/java/com/translator/proxy/protocol/mysql',
        'sdtp-protocol/src/main/java/com/translator/proxy/protocol/legacy',
    ]:
        full_path = os.path.join(BASE, d)
        if os.path.exists(full_path):
            try:
                shutil.rmtree(full_path)
                print(f'  Removed directory: {d}')
            except Exception as e:
                print(f'  Could not remove {d}: {e}')

# ==================== Main ====================
def main():
    print("=== Starting SDT Proxy architecture migration ===")
    
    print("\n--- B1: Migrating codec files ---")
    migrate_codec()
    
    print("\n--- B2: Migrating constant files ---")
    migrate_constants()
    
    print("\n--- B3: Migrating util files ---")
    migrate_util()
    
    print("\n--- B4: Migrating MySQL handlers to sub-packages ---")
    migrate_mysql_handlers()
    
    print("\n--- B5: Migrating SPI service file ---")
    migrate_spi()
    
    print("\n--- B6: Migrating test files ---")
    migrate_tests()
    
    print("\n--- C: Deleting legacy package ---")
    delete_legacy()
    
    print("\n--- D: Migrating SystemVariableInterceptor ---")
    migrate_system_var_interceptor()
    
    print("\n--- Fixing backend imports ---")
    fix_backend_imports()
    
    print("\n--- Fixing server imports ---")
    fix_server_imports()
    
    print("\n--- Cleaning up sdtp-protocol ---")
    cleanup_sdtp_protocol()
    
    print("\n=== Migration script completed ===")

if __name__ == '__main__':
    main()
