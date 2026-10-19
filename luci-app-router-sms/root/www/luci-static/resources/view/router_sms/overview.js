'use strict';
'require view';
'require rpc';
'require ui';

function method(name, params) {
    return rpc.declare({ object: 'router.sms', method: name, params: params || [], expect: { '': {} }, reject: true });
}
var api = {
    status: method('status'), messages: method('messages', ['offset']), calls: method('calls'),
    send: method('send', ['number', 'text']), remove: method('delete', ['id']),
    dial: method('dial', ['number']), accept: method('accept', ['id']), hangup: method('hangup', ['id']),
    device: method('device_status'), setup: method('device_setup'), voice: method('set_voice', ['enabled']),
    at: method('at_test'), settings: method('settings'), number: method('set_number', ['own_number'])
};
function unwrap(reply) {
    if (!reply || reply.ok !== true) throw new Error(reply && reply.error || '短信服务未响应，请检查服务是否运行');
    return reply.data;
}
function soft(promise) { return promise.then(function(r) { return { value: unwrap(r) }; }).catch(function(e) { return { error: e.message }; }); }
function hint(text) { return E('div', { 'class': 'rs-muted' }, String(text || '')); }
function card(children) { return E('section', { 'class': 'rs-card' }, children); }
function kv(name, value) { return E('div', { 'class': 'rs-kv' }, [hint(name), E('span', {}, String(value == null || value === '--' ? '—' : value))]); }
function states(s) { return ({ 'dialing': '正在拨号', 'ringing-out': '对方振铃', 'ringing-in': '来电振铃', 'active': '通话中', 'terminated': '已结束', 'waiting': '等待中', 'held': '已保持', '--': '尚未开始', 'received': '已接收', 'sent': '已发送', 'stored': '已存储', 'sending': '发送中', 'receiving': '接收中' })[s] || s || '未知'; }
function sms(raw) {
    var source = raw.data && raw.data.sms || {}, content = source.content || {}, p = source.properties || {};
    return { id: raw.id, number: content.number || '未知号码', text: content.text === '--' ? '' : content.text || '',
        time: p.timestamp === '--' ? '' : p.timestamp || '', state: p.state, outgoing: /submit/.test(p['pdu-type'] || '') };
}

return view.extend({
    handleSave: null, handleSaveApply: null, handleReset: null,
    load: function() { return Promise.all([soft(api.status()), soft(api.messages(0)), soft(api.device()), soft(api.settings())]); },
    render: function(data) {
        this.tab = 'sms'; this.items = []; this.selected = null; this.search = ''; this.next = null; this.busy = false;
        this.multi = false; this.checked = {};
        this.callClocks = {}; this.callPolling = false;
        this.writable = L.hasViewPermission ? L.hasViewPermission() : true;
        this.device = data[2].value || null; this.status = data[0].value || null;
        this.ownNumber = (data[3].value || {}).own_number || ''; this.numberDraft = this.ownNumber;
        if (data[1].value) this.mergeMessages(data[1].value, true);
        this.error = data.filter(function(d) { return d.error; }).map(function(d) { return d.error; }).join('；');
        this.page = E('div', { 'class': 'rs-app' }); this.draw();
        var self = this, ticks = 0;
        this.callTimer = setInterval(function() {
            if (!self.page.isConnected) { clearInterval(self.callTimer); return; }
            if (self.tab !== 'calls' || document.hidden) return;
            self.page.querySelectorAll('[data-call-duration]').forEach(function(e) { e.textContent = self.callDuration(e.getAttribute('data-call-duration')); });
            if (++ticks % 2 || self.busy || self.callPolling) return;
            self.callPolling = true;
            var previous = JSON.stringify(self.calls);
            api.calls().then(unwrap).then(function(r) {
                if (self.busy || self.tab !== 'calls' || !self.page.isConnected || JSON.stringify(self.calls) !== previous) return;
                var hadError = !!self.callPollError;
                self.applyCalls(r); if (JSON.stringify(r) !== previous || hadError) self.draw();
            }).catch(function() {
                if (!self.callPollError && self.page.isConnected && self.tab === 'calls') {
                    self.callPollError = '呼叫状态更新失败，请手动刷新核对。'; self.draw();
                }
            }).finally(function() { self.callPolling = false; });
        }, 1000);
        return E('div', {}, [E('link', { 'rel': 'stylesheet', 'href': L.resource('router-sms.css') + '?v=0.3.1' }), this.page]);
    },
    button: function(label, action, kind, write) {
        return E('button', { 'type': 'button', 'class': 'rs-button ' + (kind || ''),
            'disabled': this.busy || (write && !this.writable) ? '' : null, 'click': action }, label);
    },
    notify: function(message, error) { ui.addNotification(null, E('p', {}, String(message)), error ? 'danger' : 'info'); },
    execute: function(task, done) {
        if (this.busy) return Promise.resolve();
        this.busy = true; this.draw();
        return Promise.resolve().then(task).then(function(reply) { return done(unwrap(reply)); }).catch(L.bind(function(error) {
            this.notify(error.message + '。发送或拨号超时后请先刷新，避免重复操作。', true);
        }, this)).finally(L.bind(function() { this.busy = false; this.draw(); }, this));
    },
    mergeMessages: function(result, reset) {
        var seen = {}; if (reset) this.items = [];
        this.items.forEach(function(m) { seen[m.id] = true; });
        (result.messages || []).map(sms).forEach(L.bind(function(m) { if (!seen[m.id]) this.items.push(m); }, this));
        this.next = result.next;
        if ((result.errors || []).length) this.notify('部分短信读取失败，可稍后刷新。', true);
    },
    refresh: function() {
        var self = this;
        if (this.tab === 'sms') return this.execute(function() { return api.messages(0); }, function(r) { self.mergeMessages(r, true); self.error = ''; });
        if (this.tab === 'calls') return this.execute(function() { return api.calls(); }, function(r) { self.applyCalls(r); self.error = ''; });
        if (this.tab === 'settings') return this.execute(function() { return Promise.all([api.device(), api.settings()]).then(function(r) {
            return {ok: true, data: {device: unwrap(r[0]), settings: unwrap(r[1])}};
        }); }, function(r) { self.device = r.device; self.ownNumber = r.settings.own_number || ''; self.numberDraft = self.ownNumber; self.error = ''; });
        return this.execute(function() { return api.status(); }, function(r) { self.status = r; self.error = ''; });
    },
    ask: function(title, description, task, done) {
        var self = this;
        ui.showModal(title, [E('p', { 'style': 'white-space:pre-wrap;overflow-wrap:anywhere' }, description),
            E('div', { 'class': 'right rs-actions', 'style': 'justify-content:flex-end' }, [
                this.button('取消', ui.hideModal), this.button('确认', function() { ui.hideModal(); return self.execute(task, done); }, 'primary', true)
            ])]);
    },
    deleteMessages: function(ids) {
        var self = this;
        if (!ids.length || ids.length > 100) { this.notify('请选择 1–100 条短信', true); return; }
        this.ask('删除短信', '将从路由器模块永久删除选中的 ' + ids.length + ' 条短信，两端都会消失，无法恢复。', function() {
            var deleted = [], failure = '', chain = Promise.resolve();
            ids.forEach(function(id) { chain = chain.then(function() {
                if (failure) return;
                return api.remove(id).then(unwrap).then(function(r) { if (!r.ok) throw new Error('删除未确认'); deleted.push(id); })
                    .catch(function(e) { failure = e.message || '删除未完成'; });
            }); });
            return chain.then(function() { return {ok:true, data:{deleted:deleted, error:failure}}; });
        }, function(r) {
            self.items = self.items.filter(function(m) { return r.deleted.indexOf(m.id) < 0; });
            r.deleted.forEach(function(id) { delete self.checked[id]; });
            self.notify(r.error ? '已删除 ' + r.deleted.length + ' 条，' + (ids.length - r.deleted.length) + ' 条未确认删除；请先刷新核对，再选择剩余短信。' : '已删除 ' + r.deleted.length + ' 条短信', !!r.error);
        });
    },
    applyCalls: function(value) {
        var self = this, now = performance.now();
        (value.details || []).forEach(function(c) {
            var state = (((c.data || {}).call || {}).properties || {}).state, clock = self.callClocks[c.id];
            if ((state === 'active' || state === 'held') && (!clock || clock.end != null)) self.callClocks[c.id] = {start:now};
            else if (state === 'terminated' && clock && clock.end == null) clock.end = now;
        });
        this.calls = value;
        this.callPollError = '';
    },
    callDuration: function(id) {
        var clock = this.callClocks[id], seconds = clock ? Math.max(0, Math.floor(((clock.end == null ? performance.now() : clock.end) - clock.start) / 1000)) : 0;
        function pad(n) { return String(n).padStart(2,'0'); }
        return (seconds >= 3600 ? pad(Math.floor(seconds/3600)) + ':' : '') + pad(Math.floor(seconds/60)%60) + ':' + pad(seconds%60);
    },
    draw: function() {
        if (!this.page) return;
        this.page.replaceChildren();
        var self = this, tabs = [['sms', '短信会话'], ['calls', '电话'], ['status', '模块状态'], ['settings', '设置']];
        this.page.appendChild(E('header', { 'class': 'rs-header' }, [E('div', {}, [E('h2', {}, '短信与电话'),
            hint('MG1300 × DJI 一代 4G · v0.3.1')]), E('div', { 'class': 'rs-actions' }, [
            this.button('本机号码', function() { self.tab = 'settings'; self.draw(); return self.refresh().then(function() {
                var input = self.page.querySelector('[aria-label="本机号码"]'); if (input) { input.scrollIntoView({block:'center'}); input.focus(); }
            }); }),
            E('span', { 'class': 'rs-badge' + (this.error ? ' rs-warning' : '') }, this.error ? '需要检查' : 'LuCI 登录会话'),
            this.button(this.busy ? '正在读取…' : '刷新', function() { return self.refresh(); })
        ])]));
        this.page.appendChild(E('nav', { 'class': 'rs-tabs', 'role': 'tablist' }, tabs.map(function(t) {
            return E('button', { 'type': 'button', 'role': 'tab', 'aria-selected': String(self.tab === t[0]), 'disabled': self.busy ? '' : null,
                'click': function() { self.tab = t[0]; self.draw(); if (t[0] !== 'sms') self.refresh(); } }, t[1]);
        })));
        if (this.error) this.page.appendChild(E('div', { 'class': 'rs-help' }, this.error + '；可以到“设置”检查设备。'));
        var content = this.tab === 'sms' ? this.smsPage() : this.tab === 'calls' ? this.callsPage() : this.tab === 'status' ? this.statusPage() : this.settingsPage();
        this.page.appendChild(content);
        this.page.appendChild(E('div', { 'class': 'rs-status', 'aria-live': 'polite' }, this.busy ? '正在与路由器通信，请稍候…' : '所有操作在本机完成 · 不上传短信 · 不提供通话音频'));
    },
    smsPage: function() {
        var self = this;
        var search = E('input', { 'type': 'search', 'class': 'rs-input', 'placeholder': '搜索号码或短信', 'value': this.search,
            'aria-label': '搜索号码或短信' });
        var list = E('div', { 'class': 'rs-threads' });
        var renderThreads = function() {
            list.replaceChildren(); var groups = {};
            self.items.forEach(function(m) { if (!groups[m.number]) groups[m.number] = []; groups[m.number].push(m); });
            var keys = Object.keys(groups).filter(function(n) { return n.indexOf(self.search) >= 0 || groups[n].some(function(m) { return m.text.toLowerCase().indexOf(self.search.toLowerCase()) >= 0; }); });
            keys.sort(function(a,b) { return Math.max.apply(null, groups[b].map(function(m) { return Number(m.id); })) - Math.max.apply(null, groups[a].map(function(m) { return Number(m.id); })); });
            if (!keys.length) list.appendChild(E('div', { 'class': 'rs-empty' }, '暂无匹配的短信会话'));
            keys.forEach(function(n) {
                var messages = groups[n].slice().sort(function(a,b) { return Number(b.id) - Number(a.id); });
                list.appendChild(E('button', { 'type': 'button', 'class': 'rs-thread' + (self.selected === n ? ' active' : ''),
                    'disabled': self.busy ? '' : null,
                    'click': function() { if (self.selected !== n) { self.multi = false; self.checked = {}; } self.selected = n; self.draw(); } }, [E('strong', {}, n), E('span', { 'class': 'rs-count' }, messages.length + ' 条'),
                    E('span', { 'class': 'rs-preview' }, messages[0].text || '非文本短信')]));
            });
        };
        search.addEventListener('input', function() { self.search = search.value; renderThreads(); });
        renderThreads();
        var left = card([E('div', { 'class': 'rs-row' }, [E('h3', {}, '会话'), this.button('新短信', function() { self.compose(''); }, 'primary', true)]),
            hint('已加载 ' + this.items.length + ' 条 · 最新优先'), search, list]);
        if (this.next != null) left.appendChild(this.button('加载更多短信', function() { return self.execute(function() { return api.messages(self.next); }, function(r) { self.mergeMessages(r, false); }); }));
        var right;
        if (!this.selected) right = card([E('div', { 'class': 'rs-empty' }, [E('h3', {}, '消息，近在手边'), hint('选择左侧会话阅读，或新建一条短信。')])]);
        else {
            var thread = this.items.filter(L.bind(function(m) { return m.number === this.selected; }, this)).sort(function(a,b) { return Number(a.id) - Number(b.id); });
            var chosen = thread.filter(function(m) { return self.checked[m.id]; }).map(function(m) { return m.id; });
            var actions = [this.button('回复', function() { self.compose(self.selected); }, 'primary', true),
                this.button(this.multi ? '取消多选' : '多选', function() { self.multi = !self.multi; self.checked = {}; self.draw(); }, '', true)];
            if (this.multi) {
                actions.push(this.button('全选当前已加载', function() { self.checked = {}; thread.slice(0,100).forEach(function(m) { self.checked[m.id] = true; }); self.draw(); }, '', true));
                var removeSelected = this.button('删除所选 (' + chosen.length + ')', function() { self.deleteMessages(chosen); }, 'danger', true);
                if (!chosen.length) removeSelected.disabled = true;
                actions.push(removeSelected);
            }
            right = card([E('div', { 'class': 'rs-row' }, [E('h3', {}, this.selected), E('div', {'class':'rs-actions'}, actions)]),
                hint(this.multi ? '已选 ' + chosen.length + ' 条 · 每次最多 100 条，仅选择当前会话已加载的短信' : '每条短信末尾均可删除；支持多选后统一确认。'),
                E('div', { 'class': 'rs-messages' }, thread.map(function(m) {
                    var select = E('input', {type:'checkbox', 'class':'rs-checkbox', 'aria-label':'选择短信 ' + m.id,
                        checked:self.checked[m.id] ? '' : null, disabled:self.busy || !self.writable || (!self.checked[m.id] && chosen.length >= 100) ? '' : null});
                    select.addEventListener('change', function() { if (select.checked) self.checked[m.id] = true; else delete self.checked[m.id]; self.draw(); });
                    return E('article', { 'class': 'rs-message' + (m.outgoing ? ' outgoing' : '') }, [
                    self.multi ? E('label', {'class':'rs-check'}, [select, E('span',{},'选择此短信')]) : E('span'),
                    E('div', { 'class': 'rs-body' }, m.text || '非文本短信'), E('div', { 'class': 'rs-meta' }, [
                        E('span', {}, (m.outgoing ? '发出' : '收到') + ' · ' + (m.time || '时间未知') + ' · ' + states(m.state))]),
                    E('div', {'class':'rs-delete-row'}, [self.button('删除短信', function() { self.deleteMessages([m.id]); }, 'danger', true)])
                    ]); }))]);
        }
        return E('div', {}, [E('div', { 'class': 'rs-grid' }, [left, right]), hint('分页读取模块保留的短信；如需查看更早消息，请加载更多。')]);
    },
    compose: function(number, body) {
        var self = this;
        var to = E('input', { 'type': 'tel', 'class': 'rs-input', 'value': number || '', 'placeholder': '例如 +8613812345678', 'aria-label': '收件号码' });
        var text = E('textarea', { 'class': 'rs-input', 'rows': 6, 'maxlength': 1600, 'aria-label': '短信正文' }, body || '');
        var error = E('p', { 'class': 'rs-muted', 'role': 'alert' });
        ui.showModal('发送短信', [E('label', { 'class': 'rs-label' }, '收件号码'), to, E('label', { 'class': 'rs-label' }, '短信正文'), text,
            hint('最多 1600 字符，长短信可能拆分计费。已提交不等于已送达。'), error,
            E('div', { 'class': 'right rs-actions', 'style': 'justify-content:flex-end' }, [this.button('取消', ui.hideModal), this.button('发送', function() {
                var n = to.value.trim(), message = text.value;
                if (!/^\+?[0-9]{3,20}$/.test(n) || !message.trim() || message.length > 1600) { error.textContent = '请检查号码和短信长度'; return; }
                if (message.indexOf('\\') >= 0 || (message.indexOf('"') >= 0 && message.indexOf("'") >= 0)) { error.textContent = '暂不支持反斜杠或同时含两种英文引号的正文'; return; }
                self.ask('确认发送', '发送到 ' + n + '？\n\n' + message.replace(/\s+/g, ' ').slice(0, 200), function() { return api.send(n, message); }, function() { self.notify('短信已提交，请刷新会话查看状态。'); });
            }, 'primary', true)])]);
    },
    callsPage: function() {
        var self = this, v = this.calls || this.status && this.status.voice || {}, details = v.details || [];
        var enabled = v.controls_enabled === true;
        var live = details.some(function(c) { return ((c.data || {}).call || {}).properties && c.data.call.properties.state !== 'terminated'; });
        if (live) details = details.filter(function(c) { return (((c.data || {}).call || {}).properties || {}).state !== 'terminated'; });
        var number = E('input', { 'type': 'tel', 'class': 'rs-input', 'value':this.dialNumber || '', 'placeholder': '输入要拨打的号码', 'aria-label': '拨号号码', 'disabled': !enabled || live ? '' : null });
        number.addEventListener('input', function() { self.dialNumber = number.value; });
        var dial = card([E('h3', {}, '电话控制'), E('span', { 'class': 'rs-badge' + (!enabled ? ' rs-warning' : '') }, enabled ? '电话控制已启用' : '电话控制关闭'),
            E('div', { 'class': 'rs-help' }, '仅控制拨号、接听和挂断。当前未找到模块音频通路，浏览器不能听说通话。勿用于紧急电话。'),
            number, this.button('拨号', function() {
                var n = number.value.trim();
                if (!enabled || live) return;
                if (!/^\+?[0-9]{3,20}$/.test(n)) { self.notify('请输入有效的电话号码', true); return; }
                self.ask('确认拨号', '向 ' + n + ' 发起呼叫？可能产生话费。浏览器没有通话音频。', function() { return api.dial(n); }, function() { self.refresh(); });
            }, 'call-dial', true), hint(enabled ? '自动刷新呼叫状态；时长从本页首次检测到接通开始计时。' : '可在“设置”中启用实验电话控制。')]);
        // The control must remain visibly disabled as well as protected by backend ACL/gates.
        var dialButton = dial.querySelector('button'); if (!enabled || live) dialButton.disabled = true;
        var cards = [dial, E('div', { 'class': 'rs-section' }, '当前呼叫')];
        if (this.callPollError) cards.push(card([hint(this.callPollError)]));
        if (!details.length) cards.push(card([hint('当前没有呼叫。')]));
        details.forEach(function(call) {
            var p = ((call.data || {}).call || {}).properties || {}, ended = p.state === 'terminated';
            var row = [E('h3', {}, p.number || '未知号码'), E('div', { 'class': 'rs-call-state' }, states(p.state)),
                kv('方向', p.direction === 'outgoing' ? '呼出' : '呼入'), kv('音频', '未提供浏览器音频')];
            if (self.callClocks[call.id]) row.splice(2,0,E('div', {}, [E('div', {'class':'rs-call-duration','data-call-duration':call.id},self.callDuration(call.id)), hint('从本页首次检测到接通开始计时')]));
            if (enabled && !ended) {
                var buttons = [];
                if (p.state === 'ringing-in') buttons.push(self.button('接听', function() { self.ask('接听呼叫', '接听此呼叫？浏览器没有音频通道。', function() { return api.accept(call.id); }, function() { self.notify('已提交接听操作，请刷新。'); }); }, 'primary', true));
                buttons.push(self.button('挂断', function() { self.ask('挂断呼叫', '确认结束这个呼叫？', function() { return api.hangup(call.id); }, function() { self.refresh(); }); }, 'call-hangup', true));
                row.push(E('div', { 'class': 'rs-actions' }, buttons));
            }
            cards.push(card(row));
        });
        return E('div', {'class':'rs-phone'}, live ? cards.slice(1) : cards);
    },
    statusPage: function() {
        var modem = this.status && this.status.modem && this.status.modem.modem || {}, g = modem.generic || {}, p = modem['3gpp'] || {};
        return E('div', {}, [card([E('h3', {}, '移动网络'), E('div', { 'class': 'rs-stat-grid' }, [
            E('div', { 'class': 'rs-stat' }, [hint('信号强度'), E('strong', {}, ((g['signal-quality'] || {}).value || '—') + '%')]),
            E('div', { 'class': 'rs-stat' }, [hint('网络'), E('strong', {}, (g['access-technologies'] || []).join(' / ').toUpperCase() || '—')]),
            E('div', { 'class': 'rs-stat' }, [hint('模块状态'), E('strong', {}, ({ registered: '已注册', connected: '已连接' })[g.state] || g.state || '—')])
        ])]), card([E('h3', {}, '设备信息'), kv('路由器', 'GL-MG1300'), kv('模块', g.model), kv('厂商', g.manufacturer), kv('固件', g.revision),
            kv('主 AT 端口', g['primary-port']), kv('运营商', p['operator-name']), kv('模块路径', modem['dbus-path'])]),
            hint('此页面为只读查询，不修改 APN、拨号网络或模块工作模式。')]);
    },
    settingsPage: function() {
        var self = this, d = this.device || {}, hardware = d.devices || [], enabled = d.voice_enabled === true;
        var details = [kv('适配方案', 'MG1300 + DJI 一代 4G'), kv('USB 标识', '2ca3:4006 / EG25G-QDC507'),
            kv('当前路由器', d.board || '尚未检测'), kv('识别到的模块', hardware.length ? hardware.map(function(x) { return x.product; }).join(' / ') : '未发现'),
            kv('开机自动绑定', d.profile_enabled ? '已启用' : '未启用')];
        hardware.forEach(function(h) { (h.interfaces || []).forEach(function(i) { details.push(kv('USB 接口 ' + i.number + ' · 类 ' + i['class'], i.driver || '未绑定')); }); });
        details.unshift(E('h3', {}, '设备一键适配'));
        details.push(E('div', { 'class': 'rs-help' }, '只为已核对的 MG1300 与 USB 2ca3:4006 配置 option 串口驱动、ModemManager 自启和持久化热插拔。不会解绑 USB 网卡、修改 APN/IMEI 或刷写固件。不支持的设备会拒绝操作。'));
        details.push(E('div', { 'class': 'rs-actions' }, [this.button('检测设备', function() { self.refresh(); }), this.button('一键绑定并适配', function() {
            self.ask('应用 MG1300 / DJI 一代适配', '将为识别到的 QDC507 注册 option 驱动匹配（仅厂商串口接口），启用开机和插拔自动绑定、ModemManager 自启，并自动选择此物理模块。配置会先备份。不会发送短信或拨号。',
                function() { return api.setup(); }, function(r) { self.notify(r.pending ? '驱动和持久化已设置，模块正在初始化，请稍后检测。' : '设备适配完成，请刷新检测结果。'); });
        }, 'primary', true)]));
        var applyButton = details[details.length - 1].querySelectorAll('button')[1]; if (!d.compatible) applyButton.disabled = true;
        var toggle = E('input', { 'type': 'checkbox', 'class': 'rs-checkbox', 'checked': enabled ? '' : null, 'disabled': this.busy || !this.writable ? '' : null, 'aria-label': '实验电话控制' });
        var own = E('input', { 'type': 'tel', 'class': 'rs-input', 'value': this.numberDraft, 'maxlength': 21,
            'aria-label': '本机号码', 'placeholder': '输入 SIM 卡号码，可留空', 'disabled': this.busy || !this.writable ? '' : null });
        own.addEventListener('input', function() { self.numberDraft = own.value; });
        var numberCard = card([E('h3', {}, '本机号码'), own,
            hint('手动备注 SIM 卡号码，保存在路由器并与安卓端共用。不会修改 SIM 或改变发送号码；留空保存可清除。'),
            this.button('保存本机号码', function() { var value = own.value.trim();
                if (value && !/^\+?[0-9]{3,20}$/.test(value)) { self.notify('请输入 3–20 位数字，可用 + 开头', true); return; }
                self.execute(function() { return api.number(value); }, function(r) { self.ownNumber = r.own_number; self.numberDraft = r.own_number; self.notify('本机号码已保存'); });
            }, 'primary', true)]);
        return E('div', {}, [numberCard, card([E('h3', {}, 'AT 连通性测试'), hint('固定只读 AT 命令，经 ModemManager 执行。禁止 AT 的服务会显示真实错误，不会自动重启或修改配置。'),
            this.button('测试 AT 并显示结果', function() { self.execute(function() { return api.at(); }, function(r) {
                self.atResult = '命令：AT\n' + (r.ok ? '执行成功\n' + r.output : '执行失败\n' + r.error); self.draw();
            }); }, 'primary', true), E('pre', {'style': 'white-space:pre-wrap;overflow-wrap:anywhere'}, self.atResult || '尚未测试')]), card(details), card([E('h3', {}, '实验电话控制'), E('label', { 'class': 'rs-check' }, [toggle, E('span', {}, '启用拨号、接听和挂断（无音频）')]),
            hint('开启时检测 ModemManager Voice 接口。接口可用不保证所有固件、SIM 或运营商都能实际通话。'),
            this.button('保存电话设置', function() { var value = toggle.checked; self.ask('保存电话设置', value ? '启用实验电话控制？拨号可能产生话费，浏览器无法听说。' : '关闭电话控制？',
                function() { return api.voice(value); }, function() { if (self.device) self.device.voice_enabled = value; self.notify('电话设置已保存。'); }); }, 'primary', true)]),
            card([E('h3', {}, '替换旧短信管理器'), hint('本插件使用 ModemManager，不读写旧插件的 AT 配置、通讯录或转发规则。如需迁移旧插件，请先备份配置，再到软件包页卸载。'),
                E('div', { 'class': 'rs-help' }, '不要同时让旧短信守护程序和 ModemManager 占用同一 AT 端口。此安装不会删除旧插件或其数据。')])]);
    }
});
