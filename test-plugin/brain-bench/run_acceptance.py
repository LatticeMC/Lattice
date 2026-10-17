"""串行的真实 POI 验收。仅创建新测试数据目录，不修改模板或已有结果。"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import socket
import statistics
import struct
import subprocess
import time

ORDER = ['off', 'memory', 'both', 'expiry', 'memory', 'expiry', 'off', 'both',
         'expiry', 'both', 'memory', 'off', 'both', 'off', 'expiry', 'memory']


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def fields(text):
    return dict(re.findall(r'(\w+)=(\S+)', text))


def log(text):
    print(time.strftime('%H:%M:%S') + ' ' + text, flush=True)


class Rcon:
    def __init__(self, port, password):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=30)
        self.sequence = 0
        self.exchange(3, password)

    def read(self, count):
        result = b''
        while len(result) < count:
            part = self.socket.recv(count - len(result))
            if not part:
                raise RuntimeError('RCON closed')
            result += part
        return result

    def exchange(self, kind, text):
        self.sequence += 1
        body = struct.pack('<ii', self.sequence, kind) + text.encode() + b'\0\0'
        self.socket.sendall(struct.pack('<i', len(body)) + body)
        size = struct.unpack('<i', self.read(4))[0]
        if not 10 <= size <= 4_194_304:
            raise RuntimeError(f'Invalid RCON packet size: {size}')
        packet = self.read(size)
        sequence, response_kind = struct.unpack('<ii', packet[:8])
        if sequence != self.sequence:
            raise RuntimeError(f'Unexpected RCON response: {sequence}/{response_kind}')
        return packet[8:-2].decode('utf-8')

    def cmd(self, text):
        return self.exchange(2, text)

    def close(self):
        self.socket.close()


def percentile(values, proportion):
    return sorted(values)[math.ceil(len(values) * proportion) - 1]


def statistics_for(values):
    return dict(mean=statistics.mean(values), median=statistics.median(values), p95=percentile(values, .95),
                p99=percentile(values, .99), **{f'gt{n}': sum(v > n for v in values) for n in (15, 25, 50, 100)})


def verify_csv(path, status, ticks, settle):
    with Path(path).open() as stream:
        rows = list(csv.DictReader(stream))
    ids = [int(row['tick']) for row in rows]
    if len(rows) != ticks + settle or ids != list(range(ids[0], ids[0] + ticks + settle)):
        raise RuntimeError('CSV sample count or tick continuity failed')
    if [row['phase'] for row in rows] != ['settling'] * settle + ['measurement'] * ticks:
        raise RuntimeError('CSV phase order failed')
    for row in rows:
        if not math.isfinite(float(row['milliseconds'])) or float(row['milliseconds']) < 0:
            raise RuntimeError('Invalid duration')
        if int(row['endNanos']) < int(row['startNanos']):
            raise RuntimeError('Invalid callback timestamps')
    actual = statistics_for([float(row['milliseconds']) for row in rows[settle:]])
    f = fields(status)
    if f.get('processCpuNs') == 'NA' or int(f['processCpuNs']) <= 0:
        raise RuntimeError('Process CPU unavailable')
    if int(f['first']) != ids[settle] or int(f['last']) != ids[-1]:
        raise RuntimeError('CPU/tick window mismatch')
    for name, value in actual.items():
        if abs(float(f[name]) - value) > .000001:
            raise RuntimeError(f'CSV/status mismatch: {name}')
    actual.update(cpuNs=int(f['processCpuNs']), first=ids[settle], last=ids[-1],
                  all=statistics_for([float(row['milliseconds']) for row in rows]), csv=str(path),
                  measurement_values=[float(row['milliseconds']) for row in rows[settle:]])
    return actual


def verify_census(text, mode, phase=None):
    f = fields(text)
    expected = dict(live=2000, worldVillagers=2000, adult=2000, ai=2000, inBounds=2000, entityTicking=2000,
                    players=16, tickets=676, invalidMemory=0, duplicateHome=0, duplicateJob=0,
                    poiRecords=4075, poiCapacity=6400)
    errors = [f'{key}: expected {value}, got {f.get(key)}' for key, value in expected.items() if f.get(key) != str(value)]
    if phase == 'work':
        limits = dict(job=1900, workedSincePhase=1800)
    elif phase == 'meet':
        limits = dict(meeting=1800, meetGroups=25)
    elif phase == 'rest':
        limits = dict(home=1900, sleptSincePhase=1800)
    else:
        limits = {}
    errors.extend(f'{key}: minimum {value}, got {f.get(key)}' for key, value in limits.items() if int(f.get(key, -1)) < value)
    return errors


def run(args, protocol, index, mode):
    for port in (25565, 25575):
        with socket.socket() as connection:
            if connection.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError(f'Test port occupied: {port}')
    output = args.output / (f'pilot-{index}' if args.pilot else f'run-{index:02}-{mode}')
    shutil.copytree(args.fixture, output)
    plugin_target = output / 'plugins' / 'test-plugin.jar'
    if plugin_target.exists():
        raise RuntimeError('Fixture must not contain test-plugin.jar')
    shutil.copy2(args.plugin, plugin_target)
    # The workload spans 25 groups; keep every fixture villager active without
    # moving a bot through the world. Only the disposable runtime is changed.
    spigot = output / 'spigot.yml'
    spigot_text = spigot.read_text(encoding='utf-8')
    if not re.search(r'(?m)^\s+villagers:\s+32\s*$', spigot_text):
        raise RuntimeError('Fixture spigot.yml activation range changed; refusing an ambiguous workload')
    spigot.write_text(re.sub(r'(?m)^(\s+villagers:)\s+32\s*$', r'\1 0', spigot_text), encoding='utf-8')
    java = args.java_home / 'bin/java.exe'
    jcmd = args.java_home / 'bin/jcmd.exe'
    flags = [str(java), '-Xms4g', '-Xmx4g', '-Ddisable.watchdog=true', '-Dio.papermc.paper.suppress.sout.nags=true',
             '-Dlattice.schedulingBenchmark=true', '-Dlattice.brainPoiBenchmark=true', '-Dlattice.poiBfsScratch=true',
             '-Dlattice.randomTickSectionMask=false', '-Dlattice.optimizeRandomTick=false',
             '-jar', str(args.server), '--nogui', '--noconsole']
    result = dict(index=index, mode=mode, protocol=protocol, command=flags, serverSha=sha(args.server),
                  pluginSha=sha(args.plugin), censuses={}, phases={}, statuses=[])
    save(output / 'command.json', result)
    server = bots = rcon = None
    transcript = (output / 'commands.log').open('x', encoding='utf-8')

    def command(text):
        response = rcon.cmd(text)
        transcript.write(f'{time.time():.6f} {text}\n{response}\n'); transcript.flush()
        return response

    def population():
        value = command('brainpoibench status'); f = fields(value)
        result['statuses'].append(value)
        if f.get('state') != 'ready' or any(f.get(k) != str(v) for k, v in dict(live=2000, spawned=2000, players=16, tickets=676).items()):
            raise RuntimeError('Invalid population: ' + value)
        if bots.poll() is not None:
            raise RuntimeError('Bots exited during workload')
        return int(f['tick'])

    with (output / 'console.log').open('xb') as console, (output / 'bots.log').open('xb') as botlog:
        try:
            server = subprocess.Popen(flags, cwd=output, stdout=console, stderr=subprocess.STDOUT,
                                      stdin=subprocess.PIPE, creationflags=subprocess.CREATE_NO_WINDOW)
            log(f'START {index}/{mode} pid={server.pid}')
            deadline = time.monotonic() + 240
            while not re.search(r'Done \(\d', (output / 'console.log').read_text(errors='replace')):
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Server startup failed')
                time.sleep(1)
            properties = dict(line.split('=', 1) for line in (output / 'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
            rcon = Rcon(25575, properties['rcon.password'])
            command('minecraft:kill @e[type=!minecraft:player]')
            command('brainpoibench prepare')
            deadline = time.monotonic() + 300
            while fields(command('brainpoibench status')).get('state') != 'ready':
                if time.monotonic() > deadline:
                    raise RuntimeError('Fixture preparation timeout')
                time.sleep(2)
            cp = args.bot_classpath.read_text().strip().replace('\\', '/')
            botargs = output / 'bots.args'
            with botargs.open('x', encoding='utf-8') as stream:
                stream.write(f'-cp\n"{cp}"\norg.purpurmc.testplugin.activationbench.ActivationBenchBotRunner\n'
                             '--host\n127.0.0.1\n--port\n25565\n--bots\n16\n--max-bot-failures\n0\n'
                             f'--hold-seconds\n{protocol["bot_hold_seconds"]}\n--move-pattern\nnone\n--output\nbots-result.json\n')
            bots = subprocess.Popen([str(java), '@' + str(botargs)], cwd=output, stdout=botlog, stderr=subprocess.STDOUT,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            deadline = time.monotonic() + 120
            while fields(command('brainpoibench status')).get('players') != '16':
                if bots.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Bots failed to join')
                time.sleep(1)
            jfr = output / 'recording.jfr'
            response = subprocess.run([str(jcmd), str(server.pid), 'JFR.start', 'name=brainpoi',
                                       'settings=' + str(args.jfc), 'filename=' + str(jfr)], capture_output=True, text=True, check=True)
            if 'Started recording' not in response.stdout:
                raise RuntimeError('JFR did not start: ' + response.stdout + response.stderr)
            save(output / 'jfr-start.json', dict(stdout=response.stdout, stderr=response.stderr))
            start_tick = population(); start_time = time.monotonic()
            log(f'ACQUIRE {index}/{mode} ticks={protocol["acquire_ticks"]}')
            while population() - start_tick < protocol['acquire_ticks'] or time.monotonic() - start_time < 90:
                time.sleep(5)
            before = command('brainpoibench census'); result['censuses']['initial'] = before
            errors = verify_census(before, mode)
            if errors and not args.pilot:
                raise RuntimeError('Initial census failed: ' + '; '.join(errors))
            result['initial_errors'] = errors
            for phase in ('work', 'meet', 'rest'):
                command('brainpoibench phase ' + phase)
                old_csvs = set((output / 'plugins').rglob('scheduling-ticks-*.csv'))
                status = command(f'schedulingbench start {protocol["measurement_ticks"]} {protocol["settle_ticks"]}')
                if not status.startswith('started '):
                    raise RuntimeError('Measurement rejected: ' + status)
                log(f'MEASURE {index}/{mode}/{phase}')
                deadline = time.monotonic() + protocol['phase_timeout_seconds']
                while True:
                    time.sleep(5)
                    population()
                    status = command('schedulingbench status')
                    if status.startswith('complete '):
                        break
                    if not status.startswith('running ') or time.monotonic() > deadline:
                        raise RuntimeError('Measurement failed: ' + status)
                files = set((output / 'plugins').rglob('scheduling-ticks-*.csv')) - old_csvs
                if len(files) != 1:
                    raise RuntimeError('Expected exactly one new CSV')
                measurement = verify_csv(files.pop(), status, protocol['measurement_ticks'], protocol['settle_ticks'])
                measurement['status'] = status
                after = command('brainpoibench census'); result['censuses'][phase] = after
                errors = verify_census(after, mode, phase)
                measurement['functional_errors'] = errors
                result['phases'][phase] = measurement
                save(output / f'{phase}.json', measurement)
                log(f'PHASE {index}/{mode}/{phase} mean={measurement["mean"]:.3f} p99={measurement["p99"]:.3f} errors={errors}')
                if errors and not args.pilot:
                    raise RuntimeError('Functional acceptance failed: ' + '; '.join(errors))
            response = subprocess.run([str(jcmd), str(server.pid), 'JFR.stop', 'name=brainpoi'], capture_output=True, text=True, check=True)
            save(output / 'jfr-stop.json', dict(stdout=response.stdout, stderr=response.stderr))
            if not jfr.is_file() or jfr.stat().st_size < 1000:
                raise RuntimeError('JFR output missing')
            values = [v for item in result['phases'].values() for v in item['measurement_values']]
            result['aggregate'] = statistics_for(values)
            result['aggregate']['cpuNs'] = sum(item['cpuNs'] for item in result['phases'].values())
            if not args.pilot:
                log(f'BOT_HOLD {index}/{mode}')
                bots.wait(timeout=protocol['bot_hold_seconds'])
                bot_result = json.loads((output / 'bots-result.json').read_text())
                if bots.returncode != 0 or not bot_result['success'] or not all(bot_result['checks'].values()):
                    raise RuntimeError('Full bot handshake/hold check failed')
                result['bots'] = bot_result
            command('stop'); server.wait(timeout=120)
            if server.returncode != 0:
                raise RuntimeError('Server nonzero exit')
            save(output / 'result.json', result)
            log(f'COMPLETE {index}/{mode}: {result["aggregate"]}')
        except BaseException as error:
            save(output / 'failure.json', dict(error=repr(error), partial=result))
            raise
        finally:
            if rcon:
                if server and server.poll() is None:
                    try:
                        command('stop'); server.wait(timeout=60)
                    except Exception:
                        pass
                rcon.close()
            for process in (bots, server):
                if process and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait()
            transcript.close()


def main():
    parser = argparse.ArgumentParser()
    for name in ('output', 'fixture', 'server', 'plugin', 'java-home', 'bot-classpath', 'jfc', 'protocol'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--pilot', action='store_true')
    parser.add_argument('--indices', type=int, nargs='+', default=list(range(1, 17)))
    args = parser.parse_args()
    for name, value in vars(args).items():
        if isinstance(value, Path):
            setattr(args, name, value.resolve())
    protocol = json.loads(args.protocol.read_text(encoding='utf-8'))
    args.output.mkdir(parents=True, exist_ok=True)
    for index in args.indices:
        if index < 1 or index > 16:
            raise ValueError('Index must be 1..16')
        run(args, protocol, index, 'off' if args.pilot else ORDER[index - 1])


if __name__ == '__main__':
    main()
