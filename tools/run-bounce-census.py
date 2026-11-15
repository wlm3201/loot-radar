"""用 dump-mod 为多组版本/种子启动相互隔离的 Bounce 真值普查。

读取各版本的 coverage-launch.json，创建全新服务端存档，注入 -Dlootradar.coverage*
属性，写出 <out>/<version>_<seed>/truth.json。
"""
import argparse
import json
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--versions', nargs='+', default=['26.1.2', '26.2', '26.3'])
parser.add_argument('--seeds', nargs='+', default=['0', '1', '-1'])
parser.add_argument('--radius', type=int, default=5000)
parser.add_argument('--x', type=int, default=0)
parser.add_argument('--z', type=int, default=0)
parser.add_argument('--output', type=Path, default=ROOT / 'build/bounce-coverage')
args = parser.parse_args()

for version in args.versions:
    launch_path = ROOT / f'dump-mod/versions/{version}/build/coverage-launch.json'
    if not launch_path.exists():
        raise SystemExit(
            f'missing {launch_path} - build it first:\n'
            f'  gradlew -p dump-mod :v{version.replace(".", "_")}:writeCoverageLaunch')
    launch = json.loads(launch_path.read_text('utf-8'))
    for seed in args.seeds:
        case = args.output.resolve() / f'{version}_{seed}'
        truth = case / 'truth.json'
        if truth.exists():
            previous = json.loads(truth.read_text('utf-8'))
            if previous.get('complete'):
                expected = {'version': version, 'seed': seed, 'radius': args.radius, 'center': [args.x, args.z]}
                if any(previous.get(key) != value for key, value in expected.items()):
                    raise RuntimeError(f'Existing result has different parameters: {truth}')
                print(f'SKIP completed {version} seed={seed}', flush=True)
                continue
        world_dir = case / 'server'
        if (world_dir / 'world').exists():
            raise RuntimeError(f'Incomplete world exists; use a new output directory: {world_dir}')
        world_dir.mkdir(parents=True, exist_ok=True)
        (world_dir / 'eula.txt').write_text('eula=true\n', 'utf-8')
        (world_dir / 'server.properties').write_text('\n'.join([
            f'level-seed={seed}', 'level-name=world', 'online-mode=false', 'server-ip=127.0.0.1',
            'server-port=0', 'max-players=1', 'view-distance=2', 'simulation-distance=2',
            'spawn-chunk-radius=0', 'pause-when-empty-seconds=0', 'max-tick-time=0',
            'sync-chunk-writes=false', 'motd=Loot Radar isolated census', ''
        ]), 'utf-8')
        command = launch[:1] + ['-Xmx4G', '--sun-misc-unsafe-memory-access=allow',
            f'-Dlootradar.coverageRadius={args.radius}', f'-Dlootradar.coverageX={args.x}',
            f'-Dlootradar.coverageZ={args.z}', f'-Dlootradar.coverageOut={truth}'] + launch[1:]
        (case / 'command.json').write_text(json.dumps(command, indent=2), 'utf-8')
        with (case / 'server.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=world_dir, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            (case / 'pid.txt').write_text(str(process.pid), 'ascii')
            print(f'START {version} seed={seed} pid={process.pid}', flush=True)
            last = None
            while process.poll() is None:
                time.sleep(5)
                if truth.exists():
                    try:
                        state = json.loads(truth.read_text('utf-8'))
                        summary = (state['phase'], state['startsChecked'], state['candidateStarts'],
                                   state['fullChunksGenerated'], state['targetChunks'], len(state['observedCarts']))
                        if summary != last:
                            print(f'{version} seed={seed}: {summary}', flush=True)
                            last = summary
                    except (OSError, ValueError):
                        pass
        if process.returncode != 0 or not truth.exists() or not json.loads(truth.read_text('utf-8')).get('complete'):
            raise RuntimeError(f'Census failed: {case / "server.log"}')
        print(f'DONE {version} seed={seed}', flush=True)
