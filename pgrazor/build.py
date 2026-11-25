"""Offline build using the installed Android SDK and JDK; no Gradle download."""
from pathlib import Path
import argparse
import re
import os
import shutil
import subprocess
import zipfile
import uuid
import hashlib
import json

import http_dependencies

ROOT = Path(__file__).resolve().parent

def source_fingerprints(root):
    paths = [*sorted((root/'src').rglob('*.java')), *sorted((root/'controls-only').rglob('*.java')),
             root/'VERSION', root/'AndroidManifest.xml']
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}

def api_fingerprints(root, preview=False, research_api=False):
    paths = [*sorted((root/'src').rglob('*.java')), *sorted((root/'api').rglob('*.java')),
             root/'VERSION', root/'AndroidManifest.xml', root/'vendor/netty/artifacts.json',
             *sorted((root/'vendor/netty').rglob('*.md'))]
    if research_api:
        paths.extend(sorted((root/'research-api').rglob('*.java')))
    if preview:
        paths.extend(sorted((root/'preview').rglob('*.java')))
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}

def netty_notices():
    return {'assets/licenses/netty/'+path.relative_to(http_dependencies.VENDOR).as_posix(): path
            for path in sorted(http_dependencies.VENDOR.rglob('*.md'))}

def compile_dex(directory, *inputs):
    directory.mkdir()
    run(android_tool('d8'), '--min-api', '28', '--lib', ANDROID, '--output', directory, *inputs)
    if sorted(path.name for path in directory.glob('*.dex')) != ['classes.dex']:
        raise RuntimeError('Expected a single classes.dex in ' + str(directory))
    return directory/'classes.dex'

def class_jar(jar, classes):
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as out:
        for path in sorted(classes.rglob('*.class')):
            out.write(path, path.relative_to(classes).as_posix())

def release_version(root):
    version = os.environ.get('PGRAZOR_VERSION', (root/'VERSION').read_text(encoding='utf-8').strip())
    if not re.fullmatch(r'v?[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid PGRazor version')
    return 'v' + version.removeprefix('v')

def map_icon_assets():
    """Bundled POI marker PNGs plus the Pokemon name table for assets/pgrazor/map-icons.

    Only direct *.png children of assets/map-icons are taken (no recursion/traversal), and the
    archive name is always the bare file name so a crafted source path cannot escape the folder.
    pokemon-names.json (the raid-boss name lookup read by the Java map) is included under the same
    bare-name, no-symlink, stays-inside-the-folder checks. Both preview and nonroot packaging reuse
    this helper, so the name table reaches the APK at assets/pgrazor/map-icons/pokemon-names.json.
    """
    source = ROOT / 'assets/map-icons'
    icons = {}
    if source.is_dir():
        for path in sorted(source.glob('*.png')):
            if path.is_symlink() or not path.resolve().is_relative_to(source.resolve()):
                raise ValueError("Map icon escapes asset directory")
            if path.is_file():
                icons.setdefault(path.name, path)
        names = source / 'pokemon-names.json'
        if names.is_file() and not names.is_symlink() and names.resolve().is_relative_to(source.resolve()):
            icons.setdefault(names.name, names)
    return icons

def feed_icon_assets():
    """Only numeric base-species PNGs under the bundled feed sprite directory."""
    source = ROOT / 'assets/feed-icons/pokemon'
    icons = {}
    for path in sorted(source.glob('*.png')):
        if not path.stem.isascii() or not path.stem.isdecimal():
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(source.resolve()):
            raise ValueError('Feed icon escapes asset directory')
        if path.is_file():
            icons[path.name] = path
    return icons

SDK = Path(os.environ.get('ANDROID_HOME', str(Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'Android/Sdk')))
TOOLS = SDK / 'build-tools' / os.environ.get('PGRAZOR_BUILD_TOOLS', '36.1.0')
ANDROID = SDK / 'platforms' / os.environ.get('PGRAZOR_ANDROID_PLATFORM', 'android-36.1') / 'android.jar'
BUILD = ROOT / 'build'

def android_tool(name):
    """Resolve an Android SDK build tool for the current host OS."""
    if os.name == 'nt':
        suffix = '.bat' if name in ('d8', 'apksigner') else '.exe'
    else:
        suffix = ''
    return TOOLS / (name + suffix)

def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)

def signing_args():
    key = Path(os.environ.get('PGRAZOR_KEYSTORE', str(BUILD/'pgrazor-development.jks')))
    if 'PGRAZOR_KEYSTORE' in os.environ:
        if not key.is_file() or not os.environ.get('PGRAZOR_KEY_ALIAS') or not os.environ.get('PGRAZOR_STORE_PASSWORD') or not os.environ.get('PGRAZOR_KEY_PASSWORD'):
            raise RuntimeError('Configured signing key, alias or password environment variables are missing')
    else:
        os.environ.setdefault('PGRAZOR_STORE_PASSWORD','android')
        os.environ.setdefault('PGRAZOR_KEY_PASSWORD','android')
        if not key.exists():
            run('keytool','-genkeypair','-keystore',key,'-storepass:env','PGRAZOR_STORE_PASSWORD','-keypass:env','PGRAZOR_KEY_PASSWORD','-alias','pgrazor','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=PGRazor Local Development')
    return ['--ks',key,'--ks-pass','env:PGRAZOR_STORE_PASSWORD','--key-pass','env:PGRAZOR_KEY_PASSWORD','--ks-key-alias',os.environ.get('PGRAZOR_KEY_ALIAS','pgrazor')]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--test-only', action='store_true', help='Test coordinates and native ABI validation without the Android SDK')
    mode.add_argument('--nonroot', action='store_true', help='Compile the non-root game DEX with its offline API dependencies')
    mode.add_argument('--controls-only', action='store_true', help='Compile the embedded game DEX without preview dependencies or signing')
    parser.add_argument('--research-api', action='store_true', help='Include optional Research API in the non-root DEX')
    args = parser.parse_args()
    if args.research_api and not args.nonroot:
        parser.error('--research-api requires --nonroot')
    for folder in ['tests','input']:
        (BUILD / folder).mkdir(parents=True,exist_ok=True)
    (ROOT / 'input').mkdir(exist_ok=True)
    run('javac','-encoding','UTF-8','-d',BUILD/'tests',ROOT/'src/dev/pgrazor/MotionEngine.java',ROOT/'test/MotionTest.java',ROOT/'src/dev/pgrazor/RuntimeAbi.java',ROOT/'test/RuntimeAbiTest.java',ROOT/'src/dev/pgrazor/MapProjection.java',ROOT/'test/MapProjectionTest.java')
    run('java','-cp',BUILD/'tests','MotionTest')
    run('javac','-encoding','UTF-8','-d',BUILD/'tests',ROOT/'src/dev/pgrazor/MotionEngine.java',ROOT/'test/IdleWalkTest.java')
    run('java','-cp',BUILD/'tests','IdleWalkTest')
    run('javac','-encoding','UTF-8','-d',BUILD/'tests',ROOT/'src/dev/pgrazor/MotionEngine.java',ROOT/'src/dev/pgrazor/AutoWalkPlanner.java',ROOT/'test/AutoWalkTest.java')
    run('java','-cp',BUILD/'tests','AutoWalkTest')
    run('java','-cp',BUILD/'tests','RuntimeAbiTest')
    run('java','-cp',BUILD/'tests','MapProjectionTest')
    run('javac','-encoding','UTF-8','-d',BUILD/'tests',ROOT/'src/dev/pgrazor/MotionEngine.java',ROOT/'src/dev/pgrazor/LocationState.java',ROOT/'test/LocationStateTest.java')
    run('java','-cp',BUILD/'tests','LocationStateTest')
    run('javac','-encoding','UTF-8','-d',BUILD/'tests',ROOT/'src/dev/pgrazor/CameraSteering.java',ROOT/'test/CameraSteeringTest.java')
    run('java','-cp',BUILD/'tests','CameraSteeringTest')
    run('javac','-encoding','UTF-8','-d',BUILD/'tests',ROOT/'src/dev/pgrazor/CameraSteering.java',ROOT/'src/dev/pgrazor/CameraHeadingState.java',ROOT/'test/CameraHeadingStateTest.java')
    run('java','-cp',BUILD/'tests','CameraHeadingStateTest')
    run('javac','-encoding','UTF-8','-d',BUILD/'tests',ROOT/'src/dev/pgrazor/ShundoPacing.java',ROOT/'test/ShundoPacingTest.java')
    run('java','-cp',BUILD/'tests','dev.pgrazor.ShundoPacingTest')
    if args.test_only: return
    if not args.nonroot:
        classes, jar = compile_controls('controls-only', [])
        dex = compile_dex(classes/'controls-dex', jar)
        record_dex(dex, 'pgrazor', 'build-state', source_fingerprints(ROOT))
    if args.controls_only:
        return
    jars = http_dependencies.dependencies()
    classes, jar = compile_controls('api', jars, args.research_api)
    dex = compile_dex(classes/'nonroot-dex', jar, *jars)
    name = 'nonroot-research-api' if args.research_api else 'nonroot'
    record_dex(dex, 'pgrazor-' + name, name + '-build-state',
               api_fingerprints(ROOT, research_api=args.research_api), args.research_api)
    if args.research_api:
        classpath = os.pathsep.join(map(str, [ANDROID, classes, *jars]))
        run('javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
            '-classpath', classpath, '-d', BUILD/'tests',
            *[ROOT/'test'/name for name in ('HttpPreviewServerTest.java', 'LocalApiWebSocketTest.java', 'ResearchApiTest.java')])
        run('java', '-cp', str(BUILD/'tests')+os.pathsep+classpath, 'dev.pgrazor.ResearchApiTest')
    if not args.nonroot:
        build_preview(classes, jar, jars)

def record_dex(dex, filename, receipt, sources, research_api=False):
    shutil.copyfile(dex, BUILD/(filename+'.dex'))
    state = {'version':release_version(ROOT), 'sources':sources, 'research_api':research_api,
             'dex_sha256':hashlib.sha256(dex.read_bytes()).hexdigest()}
    (BUILD/(receipt+'.json')).write_text(json.dumps(state, indent=2), encoding='utf-8')

def compile_controls(variant, jars, research_api=False):
    classes = BUILD/('compile-'+uuid.uuid4().hex[:12])
    classes.mkdir()
    version_source = classes/'BuildVersion.java'
    version_source.write_text('package dev.pgrazor; final class BuildVersion { static final String NAME = "' + release_version(ROOT) + '"; }\n', encoding='utf-8')
    sources = sorted((ROOT/'src').rglob('*.java')) + sorted((ROOT/variant).rglob('*.java'))
    if variant == 'api':
        factory = classes/'LocalApiExtensionFactory.java'
        implementation = 'new ResearchApi(app, data)' if research_api else 'null'
        factory.write_text('package dev.pgrazor; final class LocalApiExtensionFactory {'
                           'static LocalApiExtension create(android.app.Application app, LocalApiData data) {'
                           f'return {implementation};' + '}}', encoding='utf-8')
        sources.append(factory)
        if research_api:
            sources.extend(sorted((ROOT/'research-api').rglob('*.java')))
    run('javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-classpath', os.pathsep.join(map(str, [ANDROID, *jars])), '-d', classes, version_source, *sources)
    if not (classes/'dev/pgrazor/BootstrapProvider.class').is_file():
        raise RuntimeError('Android compilation produced no BootstrapProvider class')
    run('javac', '-encoding', 'UTF-8', '-classpath', os.pathsep.join(map(str, [ANDROID, classes])),
        '-d', BUILD/'tests', ROOT/'src/dev/pgrazor/SavedPlaces.java', ROOT/'test/SavedPlacesValidationTest.java')
    run('java', '-cp', BUILD/'tests', 'SavedPlacesValidationTest')
    jar = classes/'controls.jar'
    class_jar(jar, classes)
    return classes, jar

def build_preview(classes, controls_jar, jars):
    preview_classes = classes/'preview-classes'
    preview_classes.mkdir()
    preview_sources = sorted((ROOT/'preview').rglob('*.java'))
    classpath = os.pathsep.join(map(str, [classes, *jars]))
    run('javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-classpath', str(ANDROID)+os.pathsep+classpath, '-d', preview_classes, *preview_sources)
    run('javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-classpath', classpath, '-d', BUILD/'tests',
        ROOT/'test/HttpPreviewServerTest.java', ROOT/'test/LocalApiControllerTest.java', ROOT/'test/LocalApiSettingsModelTest.java', ROOT/'test/LocalApiTrainerTest.java', ROOT/'test/LocalApiWebSocketTest.java')
    for test in ('HttpPreviewServerTest', 'LocalApiControllerTest', 'LocalApiSettingsModelTest', 'LocalApiTrainerTest', 'LocalApiWebSocketTest'):
        run('java', '-cp', str(BUILD/'tests')+os.pathsep+classpath, 'dev.pgrazor.'+test)
    preview_jar = classes/'preview.jar'
    class_jar(preview_jar, preview_classes)
    dex = compile_dex(classes/'preview-dex', controls_jar, preview_jar, *jars)
    unsigned=BUILD/'preview-unsigned.apk'
    run(android_tool('aapt'),'package','-f','-M',ROOT/'AndroidManifest.xml','-I',ANDROID,'-F',unsigned)
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as out:
        out.write(dex,'classes.dex')
        for name, notice in netty_notices().items():
            out.write(notice, name)
        for name,path in map_icon_assets().items(): out.write(path,'assets/pgrazor/map-icons/'+name)
        for name,path in feed_icon_assets().items(): out.write(path,'assets/pgrazor/feed-icons/pokemon/'+name)
    run(android_tool('zipalign'),'-f','4',unsigned,BUILD/'preview-aligned.apk')
    apk=BUILD/'pgrazor-preview.apk'
    run(android_tool('apksigner'),'sign',*signing_args(),'--out',apk,BUILD/'preview-aligned.apk')
    run(android_tool('apksigner'),'verify',apk)
    print('Built:', apk)
    receipt = {'sources':api_fingerprints(ROOT, preview=True), 'version':release_version(ROOT),
               'dex_sha256':hashlib.sha256(dex.read_bytes()).hexdigest()}
    (BUILD/'preview-build-state.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')

if __name__=='__main__': main()
