"""按隔离 case 编排穷举式结构源预测，或独立运行原生服务器普查。"""
import argparse
import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=['prediction', 'truth'])
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--versions', nargs='+', default=['26.2'])
    p.add_argument('--seeds', nargs='+', default=['0'])
    p.add_argument('--dimensions', nargs='+', default=['overworld', 'the_nether', 'the_end'])
    p.add_argument('--radius', type=int, default=5000)
    p.add_argument('--build', default='source-table')
    p.add_argument('--batch', type=int, default=32)
    p.add_argument('--focus', default=None,
                   help='comma-separated structure ids to predict (narrow scope)')
    p.add_argument('--sets', default=None,
                   help='comma-separated structure set names to census (narrow scope)')
    p.add_argument('--parallel-features', action='store_true',
                   help='parallel feature generation (faster than terrain prefetch)')
    args = p.parse_args()
    for version in args.versions:
        for seed in args.seeds:
            for dimension in args.dimensions:
                case = args.output.resolve() / f'{version}_{seed}' / dimension
                result = case / f'{args.mode}.json'
                expected = dict(version=version, seed=seed, dimension=f'minecraft:{dimension}', radius=args.radius, center=[0, 0])
                if result.exists():
                    state = json.loads(result.read_text('utf-8'))
                    if state.get('complete') and not state.get('error') and all(state.get(k) == v for k, v in expected.items()):
                        print('SKIP', result, flush=True)
                        continue
                    raise ValueError(f'Incomplete/incompatible existing result: {result}; use a fresh output directory')
                case.mkdir(parents=True, exist_ok=True)
                if args.mode == 'prediction':
                    command = json.loads((ROOT / f'engine/build/native-{version}-{args.build}/room-coverage-launch.json').read_text('utf-8'))
                    command[-1] = 'dev.lootradar.app.tools.SourceCoverageMain'
                    command += [str(result), str(args.radius), seed, dimension]
                    if args.focus:
                        command[1:1] = [f'-Dlootradar.focusStructures={args.focus}']
                    cwd = ROOT
                else:
                    command = json.loads((ROOT / f'dump-mod/versions/{version}/build/coverage-launch.json').read_text('utf-8'))
                    for i, arg in enumerate(command):
                        if arg.startswith('-Dfabric.addMods='):
                            jar = Path(arg.split('=', 1)[1])
                            target = case / 'census-mod.jar'
                            shutil.copy2(jar, target)
                            command[i] = f'-Dfabric.addMods={target}'
                            (case / 'census-mod.sha256').write_text(hashlib.sha256(target.read_bytes()).hexdigest(), 'ascii')
                    featureMode = ('-Dlootradar.sourceParallelFeatures=true' if args.parallel_features
                                   else '-Dlootradar.sourcePrefetchTerrain=true')
                    command[1:1] = ['-Xmx6G', '--sun-misc-unsafe-memory-access=allow',
                        f'-Dlootradar.sourceRadius={args.radius}', f'-Dlootradar.sourceDimension={dimension}',
                        f'-Dlootradar.sourceBatch={args.batch}', featureMode,
                        f'-Dlootradar.sourceOut={result}']
                    if args.sets:
                        command[1:1] = [f'-Dlootradar.sourceSets={args.sets}']
                    cwd = case / 'server'
                    if (cwd / 'world').exists():
                        raise ValueError(f'Refusing to reuse incomplete world {cwd}')
                    cwd.mkdir(exist_ok=True)
                    (cwd / 'eula.txt').write_text('eula=true\n', 'utf-8')
                    (cwd / 'server.properties').write_text('\n'.join([
                        f'level-seed={seed}', 'level-name=world', 'online-mode=false',
                        'server-ip=127.0.0.1', 'server-port=0', 'max-players=1',
                        'view-distance=2', 'simulation-distance=2', 'spawn-chunk-radius=0',
                        'pause-when-empty-seconds=0', 'max-tick-time=0', 'sync-chunk-writes=false',
                        'motd=Loot Radar source census', '']), 'utf-8')
                (case / f'{args.mode}-command.json').write_text(json.dumps(command, indent=2), 'utf-8')
                with (case / f'{args.mode}.log').open('w', encoding='utf-8') as log:
                    child = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                    (case / f'{args.mode}.pid').write_text(str(child.pid), 'ascii')
                    print('START', args.mode, version, seed, dimension, child.pid, flush=True)
                    last = None
                    while child.poll() is None:
                        time.sleep(5)
                        if result.exists():
                            try:
                                state = json.loads(result.read_text('utf-8'))
                                progress = (state.get('phase'), state.get('startsChecked'), state.get('candidateStarts'),
                                    state.get('featuresChunksGenerated'), state.get('targetChunks'), len(state.get('containers', [])))
                                if progress != last:
                                    print('PROGRESS', version, seed, dimension, progress, flush=True)
                                    last = progress
                            except (ValueError, OSError):
                                pass
                state = json.loads(result.read_text('utf-8')) if result.exists() else {}
                if child.returncode or not state.get('complete') or state.get('error') or any(state.get(k) != v for k, v in expected.items()):
                    raise RuntimeError(f'Failed: {case / (args.mode + ".log")}')
                print('DONE', args.mode, version, seed, dimension, len(state['containers']), flush=True)

if __name__ == '__main__':
    main()
