"""Run an isolated PostgreSQL cluster using already-installed PostgreSQL binaries.

All data lives under this project; the existing Windows database service is untouched.
"""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'
DATA = LOCAL / 'postgres'
PORT = 55432


def find_bin(explicit=None):
    candidates = [explicit, os.getenv('POSTGRES_BIN')]
    executable = shutil.which('pg_ctl')
    if executable:
        candidates.append(str(Path(executable).parent))
    if sys.platform == 'win32':
        import re
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                                r'SYSTEM\CurrentControlSet\Services') as services:
                n = 0
                while True:
                    try:
                        name = winreg.EnumKey(services, n)
                    except OSError:
                        break
                    n += 1
                    if name.lower().startswith('postgresql'):
                        with winreg.OpenKey(services, name) as key:
                            image = winreg.QueryValueEx(key, 'ImagePath')[0]
                            match = re.search(r'"([^"]*pg_ctl\.exe)"', image, re.I)
                            if match:
                                candidates.append(str(Path(match.group(1)).parent))
        except OSError:
            pass
    suffix = '.exe' if sys.platform == 'win32' else ''
    for candidate in candidates:
        if candidate and (Path(candidate) / f'initdb{suffix}').is_file():
            return Path(candidate)
    raise SystemExit('未找到 PostgreSQL 程序。请用 --bin 指定 bin 目录，或使用 Docker Compose。')


def command(bin_dir, executable, *args, check=True, env=None):
    suffix = '.exe' if sys.platform == 'win32' else ''
    flags = subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0
    # A server child can inherit PIPE handles on Windows and keep communicate()
    # waiting after pg_ctl has already exited. A real file avoids that lifetime.
    import tempfile
    with tempfile.TemporaryFile() as output:
        result = subprocess.run([str(bin_dir / (executable + suffix)), *map(str, args)],
                                check=False, env=env, creationflags=flags,
                                stdout=output, stderr=subprocess.STDOUT, timeout=90)
        output.seek(0)
        result.stdout = output.read()
    if check and result.returncode:
        raise subprocess.CalledProcessError(result.returncode, result.args, output=result.stdout)
    return result


def run():
    parser = argparse.ArgumentParser(description='项目专用 PostgreSQL，监听 127.0.0.1:55432')
    parser.add_argument('action', choices=['start', 'stop', 'status'])
    parser.add_argument('--bin', help='已安装的 PostgreSQL bin 目录')
    args = parser.parse_args()
    bin_dir = find_bin(args.bin)
    # Refuse a symlink/junction that redirects the database outside this workspace.
    if not DATA.resolve().is_relative_to(ROOT.resolve()):
        raise SystemExit('数据库目录必须位于项目内')
    if args.action != 'start':
        result = command(bin_dir, 'pg_ctl', '-D', DATA, args.action,
                         *(['-m', 'fast', '-w'] if args.action == 'stop' else []), check=False)
        print(result.stdout.decode(errors='replace'))
        return result.returncode
    env_path = ROOT / '.env'
    if (DATA / 'PG_VERSION').exists():
        if not env_path.exists():
            raise SystemExit('已有数据库但缺少 .env；请恢复原连接配置，不会覆盖或重置数据库密码。')
        status = command(bin_dir, 'pg_ctl', '-D', DATA, 'status', check=False)
        already_running = status.returncode == 0
    else:
        already_running = False
        if env_path.exists():
            raise SystemExit('.env 已存在。请使用其中配置的数据库；独立初始化需要尚未配置的项目。')
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1', PORT)) == 0:
                raise SystemExit(f'{PORT} 端口已被占用；不会连接或修改该实例。')
        LOCAL.mkdir(exist_ok=True)
        password = secrets.token_urlsafe(24)
        password_file = LOCAL / 'initdb-password.tmp'
        password_file.write_text(password, encoding='utf-8')
        try:
            result = command(bin_dir, 'initdb', '-D', DATA, '-U', 'ledger',
                             '--auth=scram-sha-256', '--encoding=UTF8', '--locale=C',
                             f'--pwfile={password_file}')
            print(result.stdout.decode(errors='replace'))
        finally:
            password_file.unlink(missing_ok=True)
        env_path.write_text(
            f'DATABASE_URL=postgresql://ledger:{password}@127.0.0.1:{PORT}/systemlog\n'
            'AUTO_MIGRATE=true\nSEED_DEMO=true\nAPP_HOST=127.0.0.1\nAPP_PORT=8000\n'
            'CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173\n', encoding='utf-8')
    if not already_running:
        result = command(bin_dir, 'pg_ctl', '-D', DATA, '-l', LOCAL / 'postgres.log',
                         '-o', f'-h 127.0.0.1 -p {PORT}', '-w', 'start')
        print(result.stdout.decode(errors='replace'))
    # Create only the dedicated database, never alter an existing database.
    from urllib.parse import urlsplit, unquote
    config = dict(line.strip().split('=', 1) for line in env_path.read_text(encoding='utf-8').splitlines()
                  if line.strip() and not line.lstrip().startswith('#') and '=' in line)
    url = urlsplit(config['DATABASE_URL'].strip('\"\''))
    if url.hostname != '127.0.0.1' or url.port != PORT:
        raise SystemExit('.env 与项目 PostgreSQL 实例不匹配，请检查配置。')
    db_name = unquote(url.path.lstrip('/'))
    psql_env = {**os.environ, 'PGPASSWORD': unquote(url.password or ''), 'PGCONNECT_TIMEOUT': '5'}
    psql_args = ('--no-psqlrc', '--no-password', '-h', '127.0.0.1', '-p', str(PORT),
                 '-U', unquote(url.username or ''), '-d', 'postgres', '-v', 'ON_ERROR_STOP=1')
    probe = command(bin_dir, 'psql', *psql_args, '-tAc',
                    "SELECT 1 FROM pg_database WHERE datname='" + db_name.replace("'", "''") + "'", env=psql_env)
    if probe.stdout.strip() != b'1':
        command(bin_dir, 'psql', *psql_args, '-c',
                'CREATE DATABASE "' + db_name.replace('"', '""') + '"', env=psql_env)
    print(f'项目数据库已就绪：127.0.0.1:{PORT}/{db_name}；密码已保存至 .env。')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(run())
    except subprocess.CalledProcessError as exc:
        print(exc.stdout.decode(errors='replace'), file=sys.stderr)
        sys.exit(exc.returncode)
