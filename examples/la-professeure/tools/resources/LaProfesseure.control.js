// LP_PORT is prepended by the Clojure adapter during installation.
loadAPI(18);
host.defineController("La Professeure", "Dialogue recording", "0.1", "6adb95d0-0a52-4ddc-b230-39464902d818", "La Professeure");
host.defineMidiPorts(0, 0);
var application, projectState, bank, connection, busy = false;
var CAPACITY = 256;

function reply(value) {
    if (!connection) return;
    // ASCII JSON keeps the wire encoding unambiguous with signed Java bytes.
    var s = JSON.stringify(value).replace(/[\u007f-\uffff]/g, function(c) {
        return "\\u" + ("0000" + c.charCodeAt(0).toString(16)).slice(-4);
    }) + "\n";
    var bytes = [];
    for (var i = 0; i < s.length; ++i) bytes.push(s.charCodeAt(i));
    connection.send(bytes);
}

function tracks() {
    var result = [];
    for (var i = 0; i < CAPACITY; ++i) {
        var t = bank.getItemAt(i);
        if (t.exists().get()) {
            var clip = t.clipLauncherSlotBank().getItemAt(0);
            result.push({index:i, name:t.name().get(), firstClip:clip.hasContent().get() ? clip.name().get() : null});
        }
    }
    return result;
}

function receive(data) {
    try {
        var s = "";
        for (var i = 0; i < data.length; ++i) s += String.fromCharCode(data[i] & 255);
        var request = JSON.parse(s);
        if (request.op === "status") {
            var actions = application.getActions(), saves = [];
            for (var ai = 0; ai < actions.length; ++ai)
                if (/save/i.test(String(actions[ai].getId())))
                    saves.push({id:String(actions[ai].getId()), name:String(actions[ai].getName())});
            reply({id:request.id, project:application.projectName().get(), tracks:tracks(), busy:busy,
                   modified:projectState.isModified().get(), saveActions:saves});
            return;
        }
        if (request.op !== "sync" && request.op !== "import-take" && request.op !== "save-project") throw new Error("Unsupported operation");
        if (busy) throw new Error("A sync is already in progress");
        if (!request.project || application.projectName().get() !== request.project)
            throw new Error("Project mismatch; select the explicitly requested recording project");
        if (request.op === "save-project") {
            // Only allow the ordinary Save action, never Save As / overwrite another project.
            var action = application.getAction(request.action);
            if (!action || String(action.getName()) !== "Save") throw new Error("Choose the ordinary Save action reported by status");
            action.invoke();
            reply({id:request.id, state:"save-requested"});
            return;
        }
        if (request.op === "import-take") {
            if (!/^[A-Za-z0-9_-]+$/.test(request.key) || !/^\/.+\.wav$/i.test(request.path))
                throw new Error("Import requires a managed passage ID and absolute WAV path");
            var prefix = "[LP:" + request.key + "] ";
            var matches = tracks().filter(function(t) { return t.name.indexOf(prefix) === 0; });
            if (matches.length !== 1) throw new Error("Expected exactly one managed track");
            var slot = bank.getItemAt(matches[0].index).clipLauncherSlotBank().getItemAt(0);
            if (slot.hasContent().get()) throw new Error("First clip slot is occupied; no take overwritten");
            busy = true;
            try { slot.replaceInsertionPoint().insertFile(request.path); }
            catch (error) { busy = false; throw error; }
            host.scheduleTask(function() {
                busy = false;
                if (application.projectName().get() !== request.project)
                    reply({id:request.id, error:"Project changed during import; inspect before retrying"});
                else if (!slot.hasContent().get())
                    reply({id:request.id, error:"Bitwig has not confirmed the import; inspect before retrying"});
                else reply({id:request.id, key:request.key, state:"imported", clip:slot.name().get()});
            }, 1500);
            return;
        }
        for (var slot = 0; slot < CAPACITY; ++slot)
            if (bank.getItemAt(slot).exists().get() && bank.getItemAt(slot).isGroup().get())
                throw new Error("Use a flat recording project; grouped-track synchronization is not supported yet");
        if (!Array.isArray(request.tracks) || request.tracks.length > CAPACITY - 1)
            throw new Error("Invalid track plan / capacity exceeded");
        var seen = {};
        request.tracks.forEach(function(t) {
            if (!/^\[LP:[A-Za-z0-9_-]+\] /.test(t.name) || seen[t.key]) throw new Error("Invalid/duplicate managed track");
            if (t.name.indexOf("[LP:" + t.key + "] ") !== 0) throw new Error("Track identity mismatch");
            seen[t.key] = true;
        });
        busy = true;
        applyNext(request, 0, []);
    } catch (error) {
        reply({id:request ? request.id : null, error:String(error)});
    }
}

function applyNext(request, index, changes) {
    try {
        if (application.projectName().get() !== request.project) throw new Error("Project changed during sync; stopped");
        if (index === request.tracks.length) {
            busy = false;
            reply({id:request.id, project:request.project, changes:changes, tracks:tracks()});
            return;
        }
        var desired = request.tracks[index], prefix = "[LP:" + desired.key + "] ";
        var current = tracks(), matches = current.filter(function(t) { return t.name.indexOf(prefix) === 0; });
        if (matches.length > 1) throw new Error("Duplicate managed identity " + desired.key);
        if (matches.length) {
            var found = matches[0];
            if (found.name === desired.name) changes.push({key:desired.key, state:"unchanged"});
            else if (found.name === desired.previousName) {
                bank.getItemAt(found.index).name().set(desired.name);
                changes.push({key:desired.key, state:"updated"});
            } else changes.push({key:desired.key, state:"user-name-preserved"});
            host.scheduleTask(function() { applyNext(request, index + 1, changes); }, 100);
        } else {
            if (current.length >= CAPACITY - 1) throw new Error("Track bank full; no tracks removed");
            var insert = current.length;
            application.createAudioTrack(-1);
            awaitCreated(request, index, changes, insert, 0);
        }
    } catch (error) {
        busy = false;
        reply({id:request.id, error:String(error), changes:changes});
    }
}

function awaitCreated(request, index, changes, slot, attempt) {
    host.scheduleTask(function() {
        if (application.projectName().get() !== request.project) {
            busy = false; reply({id:request.id, error:"Project changed during track creation"}); return;
        }
        var t = bank.getItemAt(slot);
        if (!t.exists().get()) {
            if (attempt < 20) { awaitCreated(request, index, changes, slot, attempt + 1); return; }
            busy = false; reply({id:request.id, error:"Timed out waiting for Bitwig track creation"}); return;
        }
        t.name().set(request.tracks[index].name);
        changes.push({key:request.tracks[index].key, state:"created"});
        host.scheduleTask(function() { applyNext(request, index + 1, changes); }, 150);
    }, 100);
}

function connect() {
    host.connectToRemoteHost("127.0.0.1", LP_PORT, function(remote) {
        connection = remote;
        remote.setReceiveCallback(receive);
        remote.setDisconnectCallback(function() { connection = null; busy = false; host.scheduleTask(connect, 2000); });
        reply({event:"connected", project:application.projectName().get()});
    });
}

function init() {
    application = host.createApplication();
    application.projectName().markInterested();
    projectState = host.getProject();
    projectState.isModified().markInterested();
    // A fixed root bank, never the selected group's children.
    bank = host.createMainTrackBank(CAPACITY, 0, 1);
    for (var i = 0; i < CAPACITY; ++i) {
        bank.getItemAt(i).exists().markInterested();
        bank.getItemAt(i).name().markInterested();
        bank.getItemAt(i).isGroup().markInterested();
        bank.getItemAt(i).clipLauncherSlotBank().getItemAt(0).hasContent().markInterested();
        bank.getItemAt(i).clipLauncherSlotBank().getItemAt(0).name().markInterested();
    }
    host.scheduleTask(connect, 500);
}
function flush() {}
function exit() { if (connection) connection.disconnect(); }
