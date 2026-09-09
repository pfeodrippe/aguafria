// Contract tests for the real controller script, with Bitwig's host API simulated.
// These do not replace the separate manual/live Bitwig acceptance test.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture(initial = []) {
    const tracks = initial.map(t => ({group: false, clips: [], ...t}));
    const tasks = [], replies = [], saves = [];
    let project = 'QA';
    const property = (get, set) => ({get, set, markInterested() {}});
    const context = {
        LP_PORT: 12345, loadAPI() {},
        host: {
            defineController() {}, defineMidiPorts() {},
            getProject: () => ({isModified: () => property(() => false)}),
            createApplication: () => ({
                projectName: () => property(() => project),
                getActions: () => [],
                getAction: id => ({getName: () => id === 'save' ? 'Save' : 'Save As', invoke: () => saves.push(id)}),
                createAudioTrack: () => tracks.push({name: 'Audio', clips: [], group: false})
            }),
            createMainTrackBank: () => ({getItemAt: i => ({
                exists: () => property(() => i < tracks.length),
                name: () => property(() => tracks[i]?.name, value => { tracks[i].name = value; }),
                isGroup: () => property(() => tracks[i]?.group),
                clipLauncherSlotBank: () => ({getItemAt: slot => ({
                    hasContent: () => property(() => !!tracks[i]?.clips[slot]),
                    name: () => property(() => tracks[i]?.clips[slot]),
                    replaceInsertionPoint: () => ({insertFile: file => {
                        if (tracks[i].importError) throw new Error('Import failed');
                        tracks[i].clips[slot] = file;
                    }})
                })})
            })}),
            scheduleTask: task => tasks.push(task),
            connectToRemoteHost: (host, port, callback) => callback({
                setReceiveCallback() {}, setDisconnectCallback() {}, disconnect() {},
                send: bytes => replies.push(JSON.parse(Buffer.from(bytes).toString('utf8')))
            })
        }
    };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../resources/LaProfesseure.control.js'), 'utf8'), context);
    function flush() {
        let limit = 5000;
        while (tasks.length) { assert.ok(limit-- > 0); tasks.shift()(); }
    }
    context.init(); flush();
    return {
        tracks, saves, setProject: value => { project = value; },
        request(command) {
            context.receive([...Buffer.from(JSON.stringify({id: 'test', project: 'QA', op: 'sync', ...command}))]);
            flush();
            return replies.at(-1);
        }
    };
}

const cue = (key, name, previousName) => ({key, name: `[LP:${key}] ${name}`, previousName});
const f = fixture([{name: 'My existing track', clips: ['recorded take'], gain: 0.7}]);
let result = f.request({tracks: [cue('one', 'la voiture — Bonjour.')]});
assert.equal(result.changes[0].state, 'created');
assert.equal(f.tracks.length, 2);
f.tracks[1].clips.push('precious voice recording');
result = f.request({tracks: [cue('one', 'la voiture — Bonjour.')]});
assert.equal(result.changes[0].state, 'unchanged');
assert.equal(f.tracks.length, 2);
result = f.request({tracks: [cue('one', 'la voiture — Bonsoir.', '[LP:one] la voiture — Bonjour.')]});
assert.equal(result.changes[0].state, 'updated');
assert.deepEqual(f.tracks[1].clips, ['precious voice recording']);
f.tracks[1].name = '[LP:one] My custom take label';
result = f.request({tracks: [cue('one', 'New script', '[LP:one] la voiture — Bonsoir.')]});
assert.equal(result.changes[0].state, 'user-name-preserved');
f.request({tracks: []});
assert.equal(f.tracks.length, 2, 'Removed passages must never delete DAW tracks');
assert.deepEqual(f.tracks[0], {name: 'My existing track', clips: ['recorded take'], gain: 0.7, group: false});
f.setProject('User project');
assert.match(f.request({tracks: [cue('two', 'Do not create')]}).error, /Project mismatch/);
assert.equal(f.tracks.length, 2);
const duplicates = fixture([{name: '[LP:one] A'}, {name: '[LP:one] B'}]);
assert.match(duplicates.request({tracks: [cue('one', 'C')]}).error, /Duplicate managed identity/);
const grouped = fixture([{name: 'User group', group: true}]);
assert.match(grouped.request({tracks: [cue('one', 'C')]}).error, /flat recording project/);
const imported = fixture([{name: '[LP:take] Voice'}]);
assert.equal(imported.request({op: 'import-take', key: 'take', path: '/tmp/take.wav'}).state, 'imported');
assert.match(imported.request({op: 'import-take', key: 'take', path: '/tmp/other.wav'}).error, /occupied/);
assert.deepEqual(imported.tracks[0].clips, ['/tmp/take.wav']);
const failed = fixture([{name: '[LP:take] Voice', importError: true}]);
assert.match(failed.request({op: 'import-take', key: 'take', path: '/tmp/take.wav'}).error, /Import failed/);
assert.equal(failed.request({op: 'status'}).busy, false, 'An import failure must release the controller');
assert.match(failed.request({op: 'save-project', action: 'save-as'}).error, /ordinary Save/);
assert.equal(failed.request({op: 'save-project', action: 'save'}).state, 'save-requested');
assert.deepEqual(failed.saves, ['save']);
failed.setProject('Other project');
assert.match(failed.request({op: 'save-project', action: 'save'}).error, /Project mismatch/);
assert.deepEqual(failed.saves, ['save']);
console.log('Controller contract tests passed: create, idempotence, safe rename, take/name preservation, archive, project/group/duplicate guards.');
