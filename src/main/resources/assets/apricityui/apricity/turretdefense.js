// 页面只读同步状态并发出按钮请求，所有判断和材料扣款都在服务端。
document.addEventListener('DOMContentLoaded', function () {
    var timer = null;
    var selectedFace = 0;
    var editingFace = -1;
    var state = {};
    var expectedType = document.getElementById('turret_defense').getAttribute('data-machine');
    function text(key) { return String(TurretDefense.text('ui.turretdefense.' + key)); }
    function set(id, value) { var element = document.getElementById(id); if (element) element.textContent = String(value); }
    function click(id, callback) { var element = document.getElementById(id); if (element) element.addEventListener('click', callback); }
    var labels = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < labels.length; i++) labels[i].textContent = text(labels[i].getAttribute('data-i18n'));
    function progress(job) {
        if (!job || !job.available) return '';
        if (job.active) return text('upgrading') + ': ' + job.paid + ' / ' + job.cost + ' FE';
        var names = []; var materials = job.materials || [];
        for (var m = 0; m < materials.length; m++) names.push(String(TurretDefense.text(materials[m].key)) + ' ×' + materials[m].count);
        return names.join(' + ') + ' | ' + job.cost + ' FE';
    }
    function refresh() {
        try { state = JSON.parse(String(TurretDefense.getStateJson())); } catch (error) { return; }
        if (!state.type || state.type !== expectedType) return;
        set('energy', text('energy') + ': ' + state.energy + ' / ' + state.maxEnergy + ' FE  T' + state.tier);
        set('upgrade_progress', progress(state.upgrade));
        if (state.type === 'base') {
            var overload = document.getElementById('overload');
            if (overload) overload.disabled = !state.overloadEnabled;
            set('core_info', text('core') + ': ' + (state.core || text('offline')));
            var installed = 0;
            for (var j = 0; j < 5; j++) { var slot = document.getElementById('module_' + j); if (slot) slot.style.display = j < state.tier ? 'block' : 'none'; }
            for (var face = 0; face < 6; face++) {
                var head = state.heads[face];
                if (head.kind) installed++;
                set('head_' + face, head.kind ? text('kind_' + head.kind) + ' T' + head.tier + '  ' + Math.round(head.health) + '/' + Math.round(head.maxHealth) + '  ' + text('shield') + ' ' + head.shield + '  ' + text('state_' + head.state) : text('empty'));
                set('head_job_' + face, head.kind ? progress(head.upgrade) : '');
                set('target_policy_' + face, text('target_policy_' + (head.targetPolicy || 'hostile')));
                set('priority_' + face, text(head.priority || 'nearest'));
                document.getElementById('priority_' + face).disabled = !state.priorityEnabled || !head.kind;
            }
            var chosen = state.heads[selectedFace] || {};
            set('editing_face', text('editing_face') + ': ' + text(['face_down','face_up','face_north','face_south','face_west','face_east'][selectedFace]));
            if (editingFace !== selectedFace) {
                document.getElementById('target_allowlist').value = (chosen.targetAllowList || []).join(', ');
                document.getElementById('target_denylist').value = (chosen.targetDenyList || []).join(', ');
                editingFace = selectedFace;
            }
            set('head_capacity', text('head_capacity') + ': ' + installed + ' / ' + [1, 2, 3, 4, 6][state.tier - 1]);
        } else {
            set('machine_status',text('health') + ': ' + Math.round(state.health) + '  ' + text('shield') + ': ' + state.shield);
            set('burn_status',text('burn') + ': ' + state.burn);
            set('trusted_list',text('trusted') + ': ' + (state.trusted || []).join(', '));
            set('scan_status',text('scan') + ': ' + state.scanInterval + ' / ' + state.enemies);
            set('deflection_range',text('deflection_range') + ': ' + state.deflectionRange);
            for (var c = 0; c < 5; c++) { var coreSlot = document.getElementById('core_module_' + c); if (coreSlot) coreSlot.style.display = c < state.tier ? 'block' : 'none'; }
            var lines = []; var bases = state.bases || [];
            for (var b = 0; b < bases.length; b++) lines.push(bases[b].pos + ' T' + bases[b].tier + '  ' + bases[b].energy + ' FE  ' + bases[b].heads.length + ' ' + text('heads'));
            set('bases_list',lines.join('\n'));
        }
    }
    click('upgrade_machine', function () { if (state.type === 'core') TurretDefense.upgradeCore(); else TurretDefense.upgradeBase(); });
    click('open_help', function () { TurretDefense.showHelp(); });
    click('cancel_machine', function () { TurretDefense.cancelUpgrade(); });
    click('overload', function () { TurretDefense.overload(); });
    click('trust_add', function () { TurretDefense.trust(String(document.getElementById('trust_id').value), true); });
    click('trust_remove', function () { TurretDefense.trust(String(document.getElementById('trust_id').value), false); });
    for (var face = 0; face < 6; face++) (function (index) {
        click('upgrade_' + index, function () { selectedFace = index; TurretDefense.requestUpgrade(index); });
        click('cancel_' + index, function () { TurretDefense.cancelHeadUpgrade(index); });
        click('details_' + index, function () { TurretDefense.showDetails(index); });
        click('edit_targets_' + index, function () { selectedFace = index; editingFace = -1; refresh(); });
        click('target_policy_' + index, function () {
            selectedFace = index;
            var policy = state.heads[index].targetPolicy || 'hostile';
            var policies = ['hostile','retaliate','allowlist','all_mobs'];
            TurretDefense.setTargetPolicy(index, policies[(policies.indexOf(policy) + 1) % policies.length]);
        });
        click('priority_' + index, function () {
            selectedFace = index;
            var current = state.heads[index].priority;
            TurretDefense.setTargetPriority(index, current === 'nearest' ? 'highest_health' : current === 'highest_health' ? 'lowest_health' : 'nearest');
        });
    })(face);
    click('apply_type', function () { TurretDefense.setTargetPriority(selectedFace,String(document.getElementById('entity_type').value)); });
    click('apply_target_lists', function () { TurretDefense.setTargetLists(selectedFace,String(document.getElementById('target_allowlist').value),String(document.getElementById('target_denylist').value)); });
    timer = setInterval(refresh, 250); refresh();
    document.addEventListener('unload', function () { if (timer !== null) clearInterval(timer); timer = null; });
});
