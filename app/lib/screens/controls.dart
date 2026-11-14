import 'dart:async';

import 'package:flutter/services.dart' show Clipboard, ClipboardData, PlatformException;
import 'package:flutter/widgets.dart';

import '../gallery.dart';
import '../native_bridge.dart';
import '../state.dart';
import '../ui/app_icon.dart';
import 'groups.dart';
import '../ui/friction.dart';
import '../ui/kit.dart';
import '../ui/tokens.dart';

/// Gallery wrapped so it can be pushed with a back button.
class GalleryPage extends StatelessWidget {
  const GalleryPage({super.key});
  @override
  Widget build(BuildContext context) => const Gallery();
}

// ---------------- Permission health ----------------

class HealthScreen extends StatefulWidget {
  const HealthScreen({super.key});
  @override
  State<HealthScreen> createState() => _HealthScreenState();
}

class _HealthScreenState extends State<HealthScreen> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    app.refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) app.refresh();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final p = app.perm;
          final acc = p.blockingActive
              ? 'Active'
              : (p.accessibilityEnabled ? 'Enabled but not connected. Turn it off and on.' : 'Off. Needed to detect Reels and Shorts.');
          return Screen(title: 'Health', subtitle: 'Real status, read from Android. Never assumed.', children: [
            _Perm(
              icon: Icons.shield_outlined,
              title: 'Accessibility',
              state: p.blockingActive ? _P.ok : _P.bad,
              detail: acc,
              onFix: () => openAccessibilityWithConsent(context),
            ),
            const SizedBox(height: S.x3),
            _Perm(
              icon: Icons.bar_chart_rounded,
              title: 'Usage access',
              state: p.usageAccess ? _P.ok : _P.bad,
              detail: p.usageAccess ? 'Granted' : 'Needed for limits and insights.',
              onFix: app.native.openUsageAccessSettings,
            ),
            const SizedBox(height: S.x3),
            _Perm(
              icon: Icons.notifications_none_rounded,
              title: 'Notification access',
              state: app.notifAccess ? _P.ok : _P.unknown,
              detail: app.notifAccess ? 'Granted' : 'Optional. Only needed to quiet noisy alerts.',
              onFix: app.native.openNotificationAccessSettings,
            ),
            if (app.diag.values.any((v) => v > 0)) ...[
              const SectionLabel('Detection checks', index: '01'),
              Group([
                for (final e in app.diag.entries.where((e) => e.value > 0))
                  Row2(title: e.key.replaceAll('_', ' ').toLowerCase(), trailing: Text('${e.value}', style: T.value.copyWith(fontSize: 16))),
              ]),
              const SizedBox(height: S.x3),
              const Notice('Times a screen did not look the way Unloop expects. Many misses for one app usually means an app update changed its layout.'),
            ],
            const SizedBox(height: S.x4),
            const Notice('On some phones, first allow restricted settings in App info, then turn Unloop on under Accessibility.'),
          ]);
        },
      );
}

enum _P { ok, bad, unknown }

class _Perm extends StatelessWidget {
  const _Perm({required this.icon, required this.title, required this.state, required this.detail, this.onFix});
  final IconData icon;
  final String title, detail;
  final _P state;
  final VoidCallback? onFix;
  @override
  Widget build(BuildContext context) => Tile(
        child: Row(children: [
          IconBadge(icon),
          const SizedBox(width: S.x4),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Text(title, style: T.heading),
                const SizedBox(width: S.x2),
                Dot(switch (state) { _P.ok => C.fg, _P.bad => C.red, _P.unknown => C.text3 }, size: 7),
              ]),
              const SizedBox(height: 2),
              Text(detail, style: T.small),
            ]),
          ),
          if (state != _P.ok && onFix != null) ...[const SizedBox(width: S.x3), Btn('Fix', small: true, expand: false, onTap: onFix)],
        ]),
      );
}

// ---------------- Usage limits ----------------

class LimitsScreen extends StatefulWidget {
  const LimitsScreen({super.key});
  @override
  State<LimitsScreen> createState() => _LimitsScreenState();
}

class _LimitsScreenState extends State<LimitsScreen> {
  /// What each limit was when this screen opened. Going back up to that is undoing your own cut, so it is free;
  /// only going above it asks you to wait.
  final Map<String, int> _base = {};
  final Map<String, int> _groupBase = {};

  /// Records a limit's starting value the first time its card is built (before anything can change it).
  String _name(Limit l) {
    _base.putIfAbsent(l.pkg, () => l.minutes);
    return l.name;
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => Screen(title: 'Limits', subtitle: 'How long each app can be used per day. When the time is up, a block screen shows until tomorrow.', children: [
          if (app.strict) ...[
            Notice('Strict mode until ${app.clock(app.strictUntilMs)}. Limits can only get tighter.', warn: true),
            const SizedBox(height: S.x3),
          ],
          if (app.limits.isEmpty) const Tile(child: Text('No limits yet. Add one below.', style: T.bodyMuted)),
          for (final l in app.limits) ...[
            Tile(
              label: _name(l),
              trailing: Tag(l.usedMinutes / l.minutes >= 0.9 ? 'Near limit' : 'On track', tone: l.usedMinutes / l.minutes >= 0.9 ? C.red : C.text2),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${dur(l.usedMinutes)} / ${dur(l.minutes)}'.toUpperCase(), style: T.value),
                const SizedBox(height: S.x4),
                DotMeter(value: (l.usedMinutes / l.minutes).clamp(0.0, 1.0)),
                const SizedBox(height: S.x5),
                Row(children: [
                  Expanded(child: Text('DAILY LIMIT', style: T.label)),
                  Stepper2(value: l.minutes, step: 15, max: app.strict ? l.minutes : 480, onChanged: (v) async {
                    final base = _base[l.pkg] ?? l.minutes;
                    if (v > base && !await askFriction(context, title: 'Raise this limit?', key: 'limit-raise')) return;
                    if (v > base) _base[l.pkg] = v;
                    l.minutes = v;
                    app.saveLimits();
                  }, format: (m) => dur(m).toUpperCase()),
                ]),
                if (!app.strict) ...[
                const SizedBox(height: S.x4),
                Align(alignment: Alignment.centerLeft, child: Btn('Remove', kind: BtnKind.secondary, small: true, expand: false, onTap: () async {
                  if (!await askFriction(context, title: 'Remove this limit?', key: 'limit-remove')) return;
                  app.limits.remove(l);
                  app.saveLimits();
                })),
                ],
              ]),
            ),
            const SizedBox(height: S.x3),
          ],
          const SizedBox(height: S.x2),
          Btn('Add a limit', kind: BtnKind.secondary, icon: Icons.add_rounded, onTap: () {
            go(context, AppPicker(
              exclude: app.limits.map((l) => l.pkg).toSet(),
              onPick: (a) {
                app.limits.add(Limit(a.pkg, a.label, 60, 0));
                app.saveLimits();
              },
            ));
          }),
          GroupsSection(base: _groupBase),
        ]),
      );
}

// ---------------- Schedules ----------------

class SchedulesScreen extends StatelessWidget {
  const SchedulesScreen({super.key});
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => Screen(title: 'Schedules', subtitle: 'Quiet hours: pick the apps, then the times.', children: [
          const Notice('During a schedule, the apps under "Applies to" cannot be opened. A calm block screen shows instead. Outside the schedule everything works normally.'),
          if (app.strict) ...[
            const SizedBox(height: S.x3),
            Notice('Strict mode until ${app.clock(app.strictUntilMs)}. Active schedules are locked.', warn: true),
          ],
          const SizedBox(height: S.x3),
          if (app.schedules.isEmpty) const Tile(child: Text('No schedules yet.', style: T.bodyMuted)),
          for (final s in app.schedules) ...[
            _Lock(locked: app.strict && s.on, child: _ScheduleCard(s, key: ObjectKey(s))),
            const SizedBox(height: S.x3),
          ],
          const SizedBox(height: S.x2),
          Btn('New schedule', kind: BtnKind.secondary, icon: Icons.add_rounded, onTap: () {
            var n = app.schedules.length + 1;
            while (app.schedules.any((x) => x.name == 'Schedule $n')) {
              n++;
            }
            app.schedules.add(Schedule('Schedule $n', 22 * 60, 7 * 60, List.filled(7, true), true));
            app.saveSchedules();
          }),
          const SectionLabel('Applies to', index: '01'),
          Group([
            for (final p in {...app.installedCatalog.map((a) => a.pkg), ...app.scheduledPkgs})
              Row2(leading: AppIcon(p), title: app.appName(p), trailing: Toggle(
                value: app.scheduledPkgs.contains(p),
                onChanged: (app.strict && app.scheduledPkgs.contains(p))
                    ? null
                    : (v) async {
                        if (!v && !await askFriction(context, title: 'Stop blocking ${app.appName(p)}?', key: 'schedule-app')) return;
                        await app.setScheduledApp(p, v);
                      },
              )),
          ]),
          const SizedBox(height: S.x3),
          Btn('Add another app', kind: BtnKind.secondary, icon: Icons.add_rounded, onTap: () {
            go(context, AppPicker(
              exclude: {...app.installedCatalog.map((a) => a.pkg), ...app.scheduledPkgs},
              onPick: (a) => app.setScheduledApp(a.pkg, true),
            ));
          }),
          const SizedBox(height: S.x3),
          const Notice('Apps that have a daily limit, or are in a group, are also blocked by every schedule.'),
        ]),
      );
}

enum _Edit { none, from, until }

class _ScheduleCard extends StatefulWidget {
  const _ScheduleCard(this.s, {super.key});
  final Schedule s;
  @override
  State<_ScheduleCard> createState() => _ScheduleCardState();
}

class _ScheduleCardState extends State<_ScheduleCard> {
  _Edit _edit = _Edit.none;
  Timer? _save;

  @override
  void dispose() {
    _save?.cancel();
    super.dispose();
  }

  Schedule get s => widget.s;

  /// A wheel spins through many values: keep them locally and save once it settles.
  void _setTime(bool from, int minutes) {
    if (from ? minutes == s.end : minutes == s.start) {
      app.say('Start and end cannot be the same time.');
      return;
    }
    setState(() => from ? s.start = minutes : s.end = minutes);
    _save?.cancel();
    _save = Timer(const Duration(milliseconds: 450), app.saveSchedules);
  }

  String _days() {
    const n = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    final d = s.days;
    if (d.every((x) => x)) return 'every day';
    if (d.take(5).every((x) => x) && !d[5] && !d[6]) return 'on weekdays';
    if (!d.take(5).any((x) => x) && d[5] && d[6]) return 'on weekends';
    final on = [for (var i = 0; i < 7; i++) if (d[i]) n[i]];
    return on.isEmpty ? 'on no days' : 'on ${on.join(', ')}';
  }

  String _sentence() {
    final pkgs = {...app.scheduledPkgs, ...app.limits.map((l) => l.pkg)};
    if (pkgs.isEmpty) return 'No apps chosen yet. Pick apps under "Applies to" below.';
    final names = pkgs.map(app.appName).toList();
    final who = names.length <= 3 ? names.join(', ') : '${names.take(3).join(', ')} and ${names.length - 3} more';
    final overnight = s.end <= s.start ? ' (ends the next morning)' : '';
    return 'Blocks $who ${_days()}, ${hm(s.start)} to ${hm(s.end)}$overnight.';
  }

  Widget _time(String label, int minutes, _Edit which) {
    final active = _edit == which;
    return Expanded(
      child: GestureDetector(
        onTap: () => setState(() => _edit = active ? _Edit.none : which),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: S.x3, horizontal: S.x4),
          decoration: BoxDecoration(
            color: C.raised,
            borderRadius: R.pill,
            border: Border.all(color: active ? C.signal : C.line),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: T.label.copyWith(fontSize: 10)),
            const SizedBox(height: 2),
            Text(hm(minutes), style: T.value),
          ]),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Tile(
        label: s.name,
        trailing: Toggle(
          value: s.on,
          onChanged: (v) async {
            if (!v && !await askFriction(context, title: 'Turn this schedule off?', key: 'schedule-off')) return;
            s.on = v;
            app.saveSchedules();
          },
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(_sentence(), style: T.body),
          const SizedBox(height: S.x4),
          Row(children: [
            _time('FROM', s.start, _Edit.from),
            const SizedBox(width: S.x3),
            _time('UNTIL', s.end, _Edit.until),
          ]),
          if (_edit != _Edit.none) ...[
            const SizedBox(height: S.x3),
            TimeWheel(
              key: ValueKey(_edit),
              minutes: _edit == _Edit.from ? s.start : s.end,
              onChanged: (v) => _setTime(_edit == _Edit.from, v),
            ),
          ],
          const SizedBox(height: S.x4),
          DayPicker(days: s.days, onChanged: (v) { s.days = v; app.saveSchedules(); }),
          const SizedBox(height: S.x4),
          Btn('Delete', kind: BtnKind.secondary, small: true, expand: false, onTap: () async {
            if (!await askFriction(context, title: 'Delete this schedule?', key: 'schedule-delete')) return;
            app.schedules.remove(s);
            app.saveSchedules();
          }),
        ]),
      );
}

// ---------------- Notifications ----------------

class NotifScreen extends StatelessWidget {
  const NotifScreen({super.key});
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => Screen(title: 'Notifications', subtitle: 'Quiet the noisy ones. Never lose a message.', children: [
          if (!app.notifAccess) ...[
            Notice('Needs Notification access. Unloop reads alerts on this phone only and never stores their content.',
                warn: true, action: 'Open settings', onAction: app.native.openNotificationAccessSettings),
            const SizedBox(height: S.x3),
          ],
          Tile(
            light: app.notif.enabled,
            label: 'Quiet noisy alerts',
            trailing: Toggle(value: app.notif.enabled, onChanged: (v) => app.saveNotif(app.notif.copyWith(enabled: v))),
            child: Text(
              app.notif.enabled ? 'Likes, follows and suggestions are snoozed, not deleted.' : 'Off. Every notification arrives as usual.',
              style: T.body.copyWith(color: app.notif.enabled ? C.ink : C.text2),
            ),
          ),
          const SectionLabel('Apps', index: '01'),
          Group([
            for (final e in app.notifApps)
              Row2(
                leading: AppIcon(e.key),
                title: e.value,
                trailing: Toggle(
                  value: app.notif.packages.contains(e.key),
                  onChanged: (v) => app.saveNotif(app.notif.copyWith(
                    packages: v ? {...app.notif.packages, e.key} : ({...app.notif.packages}..remove(e.key)),
                  )),
                ),
              ),
          ]),
          const SectionLabel('Snooze for', index: '02'),
          Tile(
            child: Row(children: [
              Expanded(child: Text('SNOOZE LENGTH', style: T.label)),
              Stepper2(value: app.notif.snoozeMinutes, step: 15, min: 15, max: 240, onChanged: (v) => app.saveNotif(app.notif.copyWith(snoozeMinutes: v)), format: (m) => dur(m).toUpperCase()),
            ]),
          ),
          const SizedBox(height: S.x3),
          Group([
            Row2(
              icon: Icons.schedule_rounded,
              title: 'Only during my schedules',
              sub: app.notif.scheduleOnly ? 'Alerts are snoozed only while a schedule is running' : 'Off: alerts are snoozed all day',
              trailing: Toggle(value: app.notif.scheduleOnly, onChanged: (v) => app.saveNotif(app.notif.copyWith(scheduleOnly: v))),
            ),
          ]),
          const SectionLabel('Never touch', index: '03'),
          if (app.notif.allowlist.isEmpty)
            const Tile(child: Text('No exceptions. Add an app whose alerts must always arrive.', style: T.bodyMuted))
          else
            Group([
              for (final p in app.notif.allowlist)
                Row2(
                  leading: AppIcon(p),
                  title: app.appName(p),
                  trailing: RoundBtn(Icons.close_rounded, size: 32, onTap: () => app.saveNotif(app.notif.copyWith(allowlist: {...app.notif.allowlist}..remove(p)))),
                ),
            ]),
          const SizedBox(height: S.x3),
          Btn('Add an app', kind: BtnKind.secondary, icon: Icons.add_rounded, onTap: () {
            go(context, AppPicker(
              exclude: app.notif.allowlist,
              onPick: (a) => app.saveNotif(app.notif.copyWith(allowlist: {...app.notif.allowlist, a.pkg})),
            ));
          }),
          const SizedBox(height: S.x3),
          const Group([
            Row2(icon: Icons.lock_outline_rounded, title: 'Messages always come through', sub: 'Direct messages are never snoozed', trailing: Toggle(value: true)),
          ]),
        ]),
      );
}

// ---------------- Backup ----------------

class BackupScreen extends StatefulWidget {
  const BackupScreen({super.key});
  @override
  State<BackupScreen> createState() => _BackupScreenState();
}

class _BackupScreenState extends State<BackupScreen> {
  String? _msg;
  bool _confirm = false;

  Future<void> _export() async {
    final json = await app.native.exportBackup();
    if (json.isEmpty) {
      setState(() => _msg = 'Nothing to export.');
      return;
    }
    await Clipboard.setData(ClipboardData(text: json));
    setState(() => _msg = 'Copied to clipboard (${json.length} characters). Paste it into a note or file to keep it.');
  }

  Future<void> _restore() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim() ?? '';
    if (text.isEmpty) {
      setState(() => _msg = 'Clipboard is empty. Copy your backup text first.');
      return;
    }
    final ImportOutcome r;
    try {
      r = await app.native.importBackup(text);
    } on PlatformException catch (e) {
      setState(() => _msg = (e.message ?? '').contains('strict_mode') ? 'Strict mode is on. Restoring could loosen your rules, so it is locked.' : 'Restore failed: ${e.message}');
      return;
    }
    if (r.ok) {
      await app.loadRules();
      await app.refresh();
      setState(() => _msg = 'Restored ${r.limits} limits, ${r.schedules} schedules, ${r.settings} settings.${r.skipped.isEmpty ? '' : ' Skipped: ${r.skipped.join(', ')}.'}');
    } else {
      setState(() => _msg = 'Restore failed: ${r.error ?? 'unknown error'}. Nothing was changed.');
    }
  }

  @override
  Widget build(BuildContext context) => Screen(title: 'Backup', subtitle: 'Your data stays yours. Export it or erase it.', children: [
        const Notice('Backup is plain text on your clipboard. Nothing leaves the phone unless you paste it somewhere.'),
        const SizedBox(height: S.x4),
        Btn('Copy backup', icon: Icons.upload_rounded, onTap: _export),
        const SizedBox(height: S.x3),
        Btn('Restore from clipboard', kind: BtnKind.secondary, icon: Icons.download_rounded, onTap: _restore),
        if (_msg != null) ...[const SizedBox(height: S.x4), Notice(_msg!)],
        const SizedBox(height: S.x8),
        Btn(
          _confirm ? 'Tap again to erase everything' : 'Delete all local data',
          kind: BtnKind.danger,
          icon: Icons.delete_outline_rounded,
          onTap: () async {
            if (!_confirm) {
              setState(() => _confirm = true);
              return;
            }
            if (!await askFriction(context, title: 'Erase everything?', key: 'wipe')) return;
            await app.wipeAll();
            if (context.mounted) Navigator.of(context).popUntil((r) => r.isFirst);
          },
        ),
      ]);
}

// ---------------- About ----------------

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});
  @override
  Widget build(BuildContext context) => Screen(title: 'About', children: [
        const Tile(
          child: Row(children: [
            LoopMark(size: 56),
            SizedBox(width: S.x5),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('Unloop', style: T.title),
                SizedBox(height: 2),
                Text('Use the apps. Skip the loop.', style: T.small),
              ]),
            ),
          ]),
        ),
        const SectionLabel('Principles', index: '01'),
        const Group([
          Row2(icon: Icons.favorite_border_rounded, title: 'Free forever', sub: 'No ads, no paywall, no purchases'),
          Row2(icon: Icons.code_rounded, title: 'Open source', sub: 'AGPL-3.0'),
          Row2(icon: Icons.wifi_off_rounded, title: 'No internet permission', sub: 'It cannot send your data anywhere'),
          Row2(icon: Icons.person_off_outlined, title: 'No account', sub: 'Nothing to sign up for'),
        ]),
        const SectionLabel('Privacy', index: '02'),
        Group([
          Row2(icon: Icons.privacy_tip_outlined, title: 'Privacy policy', sub: 'What Unloop sees and keeps', chevron: true, onTap: () => go(context, const PrivacyScreen())),
        ]),
        const SectionLabel('Version', index: '03'),
        Tile(child: Text('1.0.0 BETA', style: T.value.copyWith(fontSize: 16))),
      ]);
}

// ---------------- App picker ----------------

class AppPicker extends StatefulWidget {
  const AppPicker({super.key, required this.exclude, required this.onPick});
  final Set<String> exclude;
  final ValueChanged<InstalledApp> onPick;
  @override
  State<AppPicker> createState() => _AppPickerState();
}

class _AppPickerState extends State<AppPicker> {
  String _q = '';
  @override
  Widget build(BuildContext context) {
    final list = [
      for (final a in app.installed)
        if (!widget.exclude.contains(a.pkg) && a.label.toLowerCase().contains(_q)) a,
    ];
    return Screen(title: 'Pick an app', subtitle: 'Banking and payment apps are never listed.', children: [
      Field(hint: 'Search apps', onChanged: (v) => setState(() => _q = v.trim().toLowerCase())),
      const SizedBox(height: S.x4),
      if (list.isEmpty)
        const Tile(child: Text('No apps found.', style: T.bodyMuted))
      else
        Group([
          for (final a in list.take(100))
            Row2(
              leading: AppIcon(a.pkg),
              title: a.label,
              onTap: () {
                widget.onPick(a);
                Navigator.of(context).maybePop();
              },
            ),
        ]),
    ]);
  }
}

/// Dims and disables its child while [locked] (strict mode).
class _Lock extends StatelessWidget {
  const _Lock({required this.locked, required this.child});
  final bool locked;
  final Widget child;
  @override
  Widget build(BuildContext context) => IgnorePointer(ignoring: locked, child: Opacity(opacity: locked ? 0.5 : 1, child: child));
}

// ---------------- Learn mode ----------------

class LearnScreen extends StatefulWidget {
  const LearnScreen({super.key});
  @override
  State<LearnScreen> createState() => _LearnScreenState();
}

class _LearnScreenState extends State<LearnScreen> {
  String? _msg;

  @override
  void initState() {
    super.initState();
    app.loadLearn();
  }

  String _when(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(d.month)}-${two(d.day)} ${two(d.hour)}:${two(d.minute)}';
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final pkg = app.learnState.pkg;
          final learning = pkg.isNotEmpty;
          return Screen(title: 'Learn mode', subtitle: 'Help Unloop keep up when an app changes.', children: [
            const Notice('Saves only the shape of a screen: view names, sizes and positions. Never text, names, messages or pictures. It stays on this phone until you copy it out.'),
            const SizedBox(height: S.x3),
            CornerBrackets(
              child: Tile(
              light: learning,
              label: 'Learn mode',
              trailing: Toggle(value: learning, onChanged: learning ? (_) => app.setLearn('') : null),
              child: Text(
                learning
                    ? 'Learning ${app.appName(pkg)}. Open the screen you want, pull down the notification and tap Capture screen.'
                    : 'Off. Pick an app below to start.',
                style: T.body.copyWith(color: learning ? C.ink : C.text2),
              ),
            ),
            ),
            const SizedBox(height: S.x3),
            Group([
              Row2(
                icon: Icons.text_fields_rounded,
                title: 'Include on-screen text',
                sub: 'Off by default. Needed to learn labels like "Sponsored"',
                trailing: Toggle(value: app.learnText, onChanged: app.setLearnText),
              ),
            ]),
            if (app.learnText) ...[
              const SizedBox(height: S.x3),
              const Notice('Text capture is on. Saved screens may include names, captions and messages visible at that moment. Capture only a feed screen, and read the text before you share it.', warn: true),
            ],
            const SectionLabel('App to learn', index: '01'),
            Group([
              for (final a in app.installedCatalog)
                Row2(leading: AppIcon(a.pkg), title: a.name, trailing: Toggle(value: pkg == a.pkg, onChanged: (v) => app.setLearn(v ? a.pkg : ''))),
            ]),
            const SizedBox(height: S.x3),
            Btn('Another app', kind: BtnKind.secondary, icon: Icons.add_rounded, onTap: () {
              go(context, AppPicker(exclude: {for (final a in catalog) a.pkg}, onPick: (a) => app.setLearn(a.pkg)));
            }),
            const SectionLabel('Saved screens', index: '02'),
            if (app.captures.isEmpty)
              const Tile(child: Text('Nothing saved yet.', style: T.bodyMuted))
            else
              Group([
                for (final c in app.captures)
                  Row2(icon: Icons.photo_size_select_large_rounded, title: app.appName(c.pkg), sub: _when(c.atMs), trailing: Text('${(c.bytes / 1024).ceil()} KB', style: T.small)),
              ]),
            if (app.captures.isNotEmpty) ...[
              const SizedBox(height: S.x4),
              Btn('Copy all to clipboard', icon: Icons.upload_rounded, onTap: () async {
                final text = await app.native.exportCaptures();
                await Clipboard.setData(ClipboardData(text: text));
                setState(() => _msg = 'Copied ${app.captures.length} screens. Paste them where you want to share them.');
              }),
              const SizedBox(height: S.x3),
              Btn('Delete all', kind: BtnKind.danger, icon: Icons.delete_outline_rounded, onTap: () async {
                await app.native.deleteCaptures();
                await app.loadLearn();
                setState(() => _msg = 'Deleted.');
              }),
            ],
            if (_msg != null) ...[const SizedBox(height: S.x4), Notice(_msg!)],
            if (!app.learnState.canPost) ...[
              const SizedBox(height: S.x4),
              const Notice('Notifications are off, so the Capture button cannot appear. Allow them in Android settings.', warn: true),
            ],
            const SizedBox(height: S.x4),
            const Notice('Payment and banking apps are never offered or read.'),
          ]);
        },
      );
}

// ---------------- Pause ----------------

class PauseScreen extends StatelessWidget {
  const PauseScreen({super.key});
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => Screen(title: 'Pause', subtitle: 'A short break from all blocking, limits and covers.', children: [
          Tile(
            light: app.paused,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(app.paused ? 'PAUSED' : 'ACTIVE', style: T.value.copyWith(color: app.paused ? C.ink : C.fg)),
              const SizedBox(height: S.x2),
              Text(
                app.paused ? 'Back on at ${app.clock(app.pausedUntilMs)}.' : 'Unloop is blocking as usual.',
                style: T.body.copyWith(color: app.paused ? C.ink : C.text2),
              ),
            ]),
          ),
          const SizedBox(height: S.x4),
          if (app.paused)
            Btn('Resume now', onTap: app.resume)
          else if (app.strict)
            const Notice('Pause is locked while strict mode is on.', warn: true)
          else ...[
            const Notice('You will wait 60 seconds before the pause starts. That is on purpose.'),
            const SizedBox(height: S.x3),
            Wrap(spacing: S.x3, runSpacing: S.x3, children: [
              for (final m in const [5, 10, 30]) Btn(dur(m).toUpperCase(), kind: BtnKind.secondary, expand: false, onTap: () => pauseChecked(context, m)),
            ]),
          ],
        ]),
      );
}

// ---------------- Scroll counter ----------------

class CounterScreen extends StatefulWidget {
  const CounterScreen({super.key});
  @override
  State<CounterScreen> createState() => _CounterScreenState();
}

class _CounterScreenState extends State<CounterScreen> {
  /// Auto-kick limit when the screen opened. Going back up to it is free; going above it asks you to wait.
  late int _base = app.autoKickN;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final kick = app.autoKickN;
          return Screen(title: 'Scroll counter', subtitle: 'See how much you scroll, even where nothing is blocked.', children: [
            Group([
              Row2(
                icon: Icons.pin_outlined,
                title: 'Show the counter',
                sub: 'A small number at the top of Reels, Shorts and other endless feeds',
                trailing: Toggle(value: app.counterOn, onChanged: app.setCounterOn),
              ),
            ]),
            const SectionLabel('Auto-home', index: '01'),
            Tile(
              light: kick > 0,
              label: 'Send me home after',
              trailing: Toggle(
                value: kick > 0,
                onChanged: (v) async {
                  if (v) {
                    await app.setAutoKick(_base > 0 ? _base : 10);
                  } else {
                    if (!await askFriction(context, title: 'Turn off auto-home?', key: 'autokick')) return;
                    await app.setAutoKick(0);
                  }
                },
              ),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(
                  kick > 0 ? 'After $kick swipes in one feed you are sent to your home screen, and the count starts again.' : 'Off. The counter only shows the number.',
                  style: T.body.copyWith(color: kick > 0 ? C.ink : C.text2),
                ),
                if (kick > 0) ...[
                  const SizedBox(height: S.x4),
                  Stepper2(
                    value: kick,
                    step: 5,
                    min: 5,
                    max: app.strict ? kick : 100,
                    format: (n) => '$n SWIPES',
                    onChanged: (v) async {
                      if (v > _base && !await askFriction(context, title: 'Allow more swipes?', key: 'autokick')) return;
                      if (v > _base) _base = v;
                      await app.setAutoKick(v);
                    },
                  ),
                ],
              ]),
            ),
            const SizedBox(height: S.x4),
            const Notice('Counts Reels, Shorts, TikTok For You, Spotlight and Facebook Reels. A swipe is one count however long it takes.'),
          ]);
        },
      );
}

// ---------------- Payment apps ----------------

class PaymentScreen extends StatefulWidget {
  const PaymentScreen({super.key});
  @override
  State<PaymentScreen> createState() => _PaymentScreenState();
}

class _PaymentScreenState extends State<PaymentScreen> with WidgetsBindingObserver {
  String? _msg;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    app.refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) app.refresh();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final p = app.payment;
          final ready = p.granted && p.usage;
          return Screen(title: 'Payment apps', subtitle: 'Payment apps complain, or refuse to work, while an accessibility service is on.', children: [
            const Notice('With this on, Unloop switches itself off the moment you open a payment app and back on when you leave it. It never reads those apps.'),
            const SizedBox(height: S.x3),
            Tile(
              light: p.on,
              label: 'Step aside for payment apps',
              trailing: Toggle(value: p.on, onChanged: (ready || p.on) ? app.setPayment : null),
              child: Text(
                p.on ? 'On. Unloop is off while a payment app is open.' : (ready ? 'Off. Turn it on to let payments work.' : 'Finish the two steps below first.'),
                style: T.body.copyWith(color: p.on ? C.ink : C.text2),
              ),
            ),
            if (p.steppedAside) ...[
              const SizedBox(height: S.x3),
              Notice('Unloop is switched off right now for a payment app.', warn: true, action: 'Turn on now', onAction: () async {
                await app.native.restoreAside();
                await app.refresh();
              }),
            ],
            const SectionLabel('Setup', index: '01'),
            Group([
              Row2(
                icon: Icons.usb_rounded,
                title: '1. Allow Unloop to switch itself',
                sub: p.granted ? 'Done' : 'One command from a computer, once',
                trailing: Dot(p.granted ? C.fg : C.text3, size: 8),
              ),
              Row2(
                icon: Icons.bar_chart_rounded,
                title: '2. Usage access',
                sub: p.usage ? 'Done' : 'Needed to notice when you leave the payment app',
                trailing: p.usage ? Dot(C.fg, size: 8) : Btn('Fix', small: true, expand: false, onTap: app.native.openUsageAccessSettings),
              ),
            ]),
            if (!p.granted) ...[
              const SizedBox(height: S.x3),
              Tile(
                label: 'Run this once',
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('Connect the phone to a computer with USB debugging on, then run:', style: T.small),
                  const SizedBox(height: S.x3),
                  Text(p.command, style: T.mono),
                  const SizedBox(height: S.x4),
                  Btn('Copy the command', kind: BtnKind.secondary, small: true, expand: false, icon: Icons.copy_rounded, onTap: () async {
                    await Clipboard.setData(ClipboardData(text: p.command));
                    setState(() => _msg = 'Copied. Run it on your computer, then come back here.');
                  }),
                ]),
              ),
              const SizedBox(height: S.x3),
              const Notice('Android only lets you grant this yourself, with a command, because it is a powerful permission. Unloop uses it for one thing: its own accessibility switch.'),
            ],
            if (_msg != null) ...[const SizedBox(height: S.x3), Notice(_msg!)],
            const SectionLabel('Apps it steps aside for', index: '02'),
            if (p.apps.isEmpty)
              const Tile(child: Text('No payment or banking apps found on this phone.', style: T.bodyMuted))
            else
              Group([for (final a in p.apps) Row2(leading: AppIcon(a.pkg), title: a.label)]),
            const SizedBox(height: S.x4),
            const Notice('Good to know: the very first moment a payment app opens it may still notice. If it complains, close it and open it again. Unloop is off for the whole time it is open and returns about two seconds after you leave.'),
          ]);
        },
      );
}

// ---------------- Accessibility disclosure ----------------

/// Opens Android's Accessibility settings, but only after the user has read and agreed to what the service does.
/// Google Play requires this prominent, in-app disclosure with a clear choice before the permission is requested.
Future<void> openAccessibilityWithConsent(BuildContext context) async {
  if (!await app.native.flag('a11y_consent')) {
    if (!context.mounted) return;
    final ok = await go<bool>(context, const DisclosureScreen()) ?? false;
    if (!ok) return;
    await app.native.setFlag('a11y_consent', true);
  }
  await app.native.openAccessibilitySettings();
}

/// If Accessibility was switched on without going through the app (straight from Android settings), ask for consent as
/// soon as the app is opened. Agreeing keeps it on; declining takes the user to the Android page to turn it off.
bool _asking = false;
Future<void> askConsentIfNeeded(BuildContext context) async {
  if (_asking || !app.perm.accessibilityEnabled || await app.native.flag('a11y_consent')) return;
  if (!context.mounted) return;
  _asking = true;
  try {
    final ok = await go<bool>(context, const DisclosureScreen(alreadyOn: true)) ?? false;
    if (ok) {
      await app.native.setFlag('a11y_consent', true);
    } else {
      await app.native.openAccessibilitySettings();
    }
  } finally {
    _asking = false;
  }
}

class DisclosureScreen extends StatelessWidget {
  const DisclosureScreen({super.key, this.alreadyOn = false});

  /// True when Accessibility is already on and the user is being asked to confirm it.
  final bool alreadyOn;
  @override
  Widget build(BuildContext context) => Screen(
      title: alreadyOn ? 'Please confirm' : 'Before you turn this on',
      subtitle: alreadyOn
          ? 'Unloop\u2019s Accessibility service is on. Please read what that means and confirm.'
          : 'Unloop uses Android\u2019s Accessibility service. Please read what that means.',
      children: [
        const Tile(label: 'What it is used for', child: Text('To see which screen is open in the apps you choose, for example the Reels tab or the Shorts player, by looking at the names and positions of buttons and views. Unloop can then go back, tap a tab, or draw a cover over a button.', style: T.body)),
        const SizedBox(height: S.x3),
        const Tile(label: 'What it can see', child: Text('The structure of screens in supported apps and in apps you set limits on. It does not read what you type, your passwords or your messages, and it never reads payment or banking apps.', style: T.body)),
        const SizedBox(height: S.x3),
        const Tile(label: 'What happens to it', child: Text('Screen content is never stored or sent. Unloop has no internet permission, no account, no ads and no analytics. Only your settings, limits and usage totals stay on this phone.', style: T.body)),
        const SizedBox(height: S.x3),
        const Tile(label: 'You stay in control', child: Text('You can turn it off at any time in Android Settings > Accessibility. Unloop is free and open source.', style: T.body)),
        const SizedBox(height: S.x6),
        Btn('I understand and agree', icon: Icons.check_rounded, onTap: () => Navigator.of(context).pop(true)),
        const SizedBox(height: S.x3),
        Btn(alreadyOn ? 'Turn Unloop off' : 'Not now', kind: BtnKind.secondary, onTap: () => Navigator.of(context).pop(false)),
      ]);
}

// ---------------- Privacy policy ----------------

class PrivacyScreen extends StatelessWidget {
  const PrivacyScreen({super.key});
  @override
  Widget build(BuildContext context) => Screen(title: 'Privacy policy', children: [
        for (final p in _policy) ...[
          Tile(label: p.$1, child: Text(p.$2, style: T.body)),
          const SizedBox(height: S.x3),
        ],
      ]);
}

const _policy = <(String, String)>[
  ('Short version', 'Unloop works entirely on your phone. It has no internet permission, no account, no ads and no analytics. Nothing about you or your screen is sent anywhere.'),
  ('What Unloop looks at', 'With your permission, Android’s Accessibility service shows Unloop the structure of screens in the apps it supports and in apps you set limits on: names and positions of buttons and views, and which tab is open. It uses this only to recognise Reels, Shorts and similar screens so it can go back, tap a tab or cover a button. It does not read what you type, passwords or messages, and it never reads payment or banking apps. Screen content is not stored or sent.'),
  ('What stays on your phone', 'Your settings, limits, schedules, groups, daily usage totals per app (from Android usage access), and counters such as how many times Unloop stepped back. They are kept in the app’s private storage, are excluded from Android cloud backup, and are deleted by Settings > Backup > Delete all local data or by uninstalling.'),
  ('Permissions and why', 'Accessibility service: to recognise the screens above. Usage access: to measure time per app for limits and insights. Notification access (optional): to snooze noisy notifications from apps you choose; message-style notifications are left alone and notification text is not stored. Notifications: to show a calm limit reminder. Run at startup: to restore schedules after a restart. Draw over apps is not used.'),
  ('Optional: Learn mode', 'If you turn on Learn mode, Unloop saves the layout of screens you choose to capture (view names, sizes and positions) on your phone. Text is left out unless you separately switch on “Include on-screen text”. Nothing leaves your phone unless you copy it out yourself.'),
  ('Backup', 'Backup copies your rules to the clipboard as plain text. Nothing is uploaded.'),
  ('Children', 'Unloop is not directed at children under 13 and collects no personal information from anyone.'),
  ('Changes and contact', 'If this policy changes the new version ships with the app. Questions: write to sarath@gainandshine.com, or open an issue in the project repository.'),
];
