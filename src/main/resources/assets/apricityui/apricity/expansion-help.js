// 帮助与详情只展示服务端状态，导航请求仍由原机器的权限与距离校验处理。
document.addEventListener('DOMContentLoaded', function () {
    var timer = null;
    var lastJson = null;
    var currentFace = -1;
    var faces = ['down', 'up', 'north', 'south', 'west', 'east'];
    function text(key) { return String(TurretDefense.text('ui.turretdefense.' + key)); }
    function translate(key) { return String(TurretDefense.text(String(key))); }
    function set(id, value) { var element = document.getElementById(id); if (element) element.textContent = String(value); }
    function display(id, visible) { var element = document.getElementById(id); if (element) element.style.display = visible ? 'block' : 'none'; }
    function click(id, callback) { var element = document.getElementById(id); if (element) element.addEventListener('click', callback); }
    function number(value) { var result = Number(value); return isFinite(result) ? String(Math.round(result * 100) / 100) : text('details_unknown'); }
    function named(prefix, value) {
        if (value === undefined || value === null || value === '') return text('details_unknown');
        var key = 'ui.turretdefense.' + prefix + value;
        var result = translate(key);
        return result === key ? String(value) : result;
    }
    function line(label, value) { return text(label) + ': ' + value; }
    function progress(job) {
        if (!job || !job.available) return text('details_no_upgrade');
        var output = [];
        if (job.active) output.push(line('upgrading', number(job.paid) + ' / ' + number(job.cost) + ' FE'));
        else output.push(line('details_upgrade_energy', number(job.cost) + ' FE'));
        var materials = job.materials || [];
        for (var i = 0; i < materials.length; i++) output.push(translate(materials[i].key) + ' ×' + number(materials[i].count));
        return output.join('\n');
    }
    var labels = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < labels.length; i++) labels[i].textContent = text(labels[i].getAttribute('data-i18n'));
    click('back_machine', function () { TurretDefense.backToMachine(); });
    click('open_help', function () { TurretDefense.showHelp(); });
    click('switch_mode', function () { if (currentFace >= 0) TurretDefense.switchMode(currentFace); });
    for (var face = 0; face < 6; face++) (function (index) {
        click('detail_face_' + index, function () { TurretDefense.showDetails(index); });
    })(face);
    // 无同步数据或面上塔头已损毁时隐藏旧详情，防止继续显示过期战绩。
    function refresh() {
        var raw = String(TurretDefense.getStateJson());
        if (raw === lastJson) return;
        var state;
        try { state = JSON.parse(raw); if (!state || typeof state !== 'object') state = {}; } catch (error) {
            display('detail_content', false);
            set('detail_connection', text('details_waiting'));
            set('help_connection', text('details_waiting'));
            return;
        }
        lastJson = raw;
        set('help_connection', state.type ? text('help_connection_hint') : text('details_waiting'));
        if (!document.getElementById('detail_content')) return;
        var faceIndex = Number(state.detailFace);
        currentFace = faceIndex >= 0 && faceIndex < 6 ? faceIndex : -1;
        var heads = state.heads || (state.base && state.base.heads) || [];
        if (state.page !== 'details' || !state.type || faceIndex < 0 || faceIndex > 5 || Math.floor(faceIndex) !== faceIndex) {
            display('detail_content', false);
            set('detail_connection', text('details_waiting'));
            return;
        }
        var head = heads[faceIndex];
        var modeButton = document.getElementById('switch_mode');
        if (modeButton) modeButton.disabled = !head || head.modeSwitch !== true;
        var faceName = text('face_' + faces[faceIndex]);
        set('detail_connection', line('details_face', faceName));
        if (!head || !head.kind) {
            display('detail_content', false);
            set('detail_connection', faceName + ': ' + text('details_empty'));
            return;
        }
        display('detail_content', true);
        set('detail_name', named('kind_', head.kind) + ' T' + number(head.tier));
        set('detail_status', line('details_state', named('state_', head.state)) + '\n' + line('health', number(head.health) + ' / ' + number(head.maxHealth)) + '\n' + line('shield', number(head.shield) + ' / ' + number(head.maxShield)) + '\n' + line('priority', named('', head.priority)));
        var stats = head.stats || {};
        var continuous = stats.continuous === true || head.kind === 'laser' || head.kind === 'fusion';
        set('detail_stats', [line(continuous ? 'details_damage_second' : 'details_damage', number(stats.damage)), line('details_range', number(stats.range) + ' ' + text('details_blocks')), line(continuous ? 'details_energy_second' : 'details_energy', number(stats.energy) + ' FE'), line('details_cooldown', number(stats.cooldown) + ' tick'), line('details_aoe', number(stats.aoe) + ' ' + text('details_blocks')), line('details_chain', number(stats.chain)), line('details_pierce', number(stats.pierce))].join('\n'));
        set('detail_supply', line('details_ammo', head.ammo ? translate(head.ammo) : text('details_no_ammo')) + '\n' + line('details_mode', named('mode_', head.mode)));
        var modules = head.modules || [];
        var moduleNames = [];
        for (var m = 0; m < modules.length; m++) moduleNames.push(translate(modules[m]));
        set('detail_modules', moduleNames.length ? moduleNames.join('\n') : text('details_no_modules'));
        set('detail_record', line('details_kills', number(head.kills)) + '\n' + line('details_xp', number(head.xp)));
        set('detail_upgrade', progress(head.upgrade));
    }
    refresh();
    timer = setInterval(refresh, 500);
    document.addEventListener('unload', function () { if (timer !== null) clearInterval(timer); timer = null; });
});
