import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/widgets.dart';

import '../state.dart';
import '../ui/kit.dart';
import '../ui/tokens.dart';
import '../ui/app_icon.dart';
import '../ui/friction.dart';
import 'controls.dart';

class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> with WidgetsBindingObserver {
  int _i = 0;
  static const _items = [
    NavItem(Icons.home_rounded, 'Home'),
    NavItem(Icons.apps_rounded, 'Apps'),
    NavItem(Icons.bar_chart_rounded, 'Insights'),
    NavItem(Icons.settings_rounded, 'Settings'),
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Accessibility may have been switched on straight from Android settings: make sure the user has agreed.
    WidgetsBinding.instance.addPostFrameCallback((_) => app.refresh().then((_) {
          if (mounted) askConsentIfNeeded(context);
        }));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) {
      // user returns from system settings
      app.refresh().then((_) {
        if (mounted) askConsentIfNeeded(context);
      });
    }
  }

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: C.bg,
        child: Stack(children: [
          Column(children: [
          Expanded(
            child: IndexedStack(index: _i, children: [
              HomeTab(goTo: (i) => setState(() => _i = i)),
              const AppsTab(),
              const InsightsTab(),
              const SettingsTab(),
            ]),
          ),
          BottomNav(items: _items, index: _i, onChanged: (v) => setState(() => _i = v)),
          ]),
          Positioned.fill(
            child: IgnorePointer(
              child: ListenableBuilder(
                listenable: app,
                builder: (context, _) => app.toast == null
                    ? const SizedBox.shrink()
                    : Align(
                        alignment: Alignment.topCenter,
                        child: Padding(
                          padding: EdgeInsets.fromLTRB(S.x4, MediaQuery.of(context).padding.top + S.x3, S.x4, 0),
                          child: Notice(app.toast!, warn: true),
                        ),
                      ),
              ),
            ),
          ),
        ]),
      );
}

// ---------------- Home ----------------

class HomeTab extends StatelessWidget {
  const HomeTab({super.key, required this.goTo});
  final ValueChanged<int> goTo;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final on = app.perm.blockingActive && app.masterOn;
          final next = app.schedules.where((s) => s.on).firstOrNull;
          return Screen(back: false, children: [
            Row(children: [
              const LoopMark(size: 34),
              const SizedBox(width: S.x3),
              Text('UNLOOP', style: T.label.copyWith(color: C.fg, letterSpacing: 3)),
            ]),
            const SizedBox(height: S.x6),
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Expanded(
                child: Tile(
                  label: 'Stepped back',
                  index: '01',
                  child: Column(children: [
                    DotRing(progress: on ? (app.blocked % 20) / 20 + 0.05 : 0, size: 132, child: DotText('${app.blocked}', pitch: 5)),
                    const SizedBox(height: S.x4),
                    Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Metric('${app.blocksToday}', 'Today'), Metric('${app.blocksWeek}', 'This week')]),
                  ]),
                ),
              ),
              const SizedBox(width: S.x3),
              Expanded(
                child: Column(children: [
                  Tile(
                    light: true,
                    label: 'Protection',
                    trailing: Dot(on ? C.ink : C.red),
                    onTap: () => go(context, const HealthScreen()),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(on ? 'ON' : 'OFF', style: T.value.copyWith(color: C.ink)),
                      const SizedBox(height: S.x3),
                      Text(on ? 'ACCESSIBILITY' : (app.masterOn ? 'TAP TO FIX' : 'SWITCHED OFF'), style: T.label.copyWith(color: C.ink.withValues(alpha: 0.6), fontSize: 10)),
                      const SizedBox(height: S.x3),
                      DotPulse(on: on, color: C.ink, height: 22),
                    ]),
                  ),
                  const SizedBox(height: S.x3),
                  Tile(
                    label: 'Next',
                    onTap: () => go(context, const SchedulesScreen()),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(next == null ? '--:--' : hm(next.start), style: T.value),
                      const SizedBox(height: S.x3),
                      Text((next?.name ?? 'No schedule').toUpperCase(), style: T.label.copyWith(fontSize: 10, color: C.text3)),
                    ]),
                  ),
                ]),
              ),
            ]),
            if (!on) ...[
              const SizedBox(height: S.x3),
              Notice(app.masterOn ? 'Selective blocking is not running.' : 'Selective blocking is switched off in Settings.', warn: true, action: 'Fix', onAction: () => app.masterOn ? go(context, const HealthScreen()) : goTo(3)),
            ],
            if (app.paused) ...[
              const SizedBox(height: S.x3),
              Tile(
                child: Row(children: [
                  Expanded(child: Text('Paused until ${app.clock(app.pausedUntilMs)}', style: T.body)),
                  Btn('Resume', small: true, expand: false, onTap: app.resume),
                ]),
              ),
            ],
            const SectionLabel('Blocking', index: '02'),
            Group([
              for (final a in app.installedCatalog)
                Row2(
                  leading: AppIcon(a.pkg),
                  title: a.surfaces.first.label,
                  sub: a.beta ? '${a.name} · beta' : a.name,
                  onTap: () => go(context, AppDetail(a)),
                  trailing: Toggle(value: app.surfaceOn[a.surfaces.first.id] ?? true, onChanged: (v) => setSurfaceChecked(context, a.surfaces.first.id, v, a.surfaces.first.label)),
                ),
            ]),
            const SectionLabel('Controls', index: '03'),
            Tile(
              child: Wrap(spacing: S.x3, runSpacing: S.x3, children: [
                _Quick(Icons.timer_outlined, 'Limits', () => go(context, const LimitsScreen())),
                _Quick(Icons.nights_stay_outlined, 'Schedule', () => go(context, const SchedulesScreen())),
                _Quick(Icons.notifications_none_rounded, 'Alerts', () => go(context, const NotifScreen())),
              ]),
            ),
          ]);
        },
      );
}

class _Quick extends StatelessWidget {
  const _Quick(this.icon, this.label, this.onTap);
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: 64,
        child: Column(children: [
          RoundBtn(icon, onTap: onTap),
          const SizedBox(height: S.x2),
          Text(label.toUpperCase(), style: T.label.copyWith(fontSize: 9, letterSpacing: 1)),
        ]),
      );
}

// ---------------- Apps ----------------

class AppsTab extends StatelessWidget {
  const AppsTab({super.key});
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => Screen(back: false, title: 'Apps', subtitle: 'Pick what to skip inside each app.', children: [
          for (final a in app.installedCatalog) ...[
            Tile(
              onTap: () => go(context, AppDetail(a)),
              child: Row(children: [
                AppIcon(a.pkg, size: 48),
                const SizedBox(width: S.x4),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(a.name, style: T.heading),
                    const SizedBox(height: 2),
                    Text(a.surfaces.map((s) => s.label).join(', '), style: T.small),
                  ]),
                ),
                if (a.beta) ...[const Tag('Beta'), const SizedBox(width: S.x2)],
                a.surfaces.any((s) => app.surfaceOn[s.id] ?? true) ? const Tag('On', tone: C.fg, filled: true) : const Tag('Off'),
              ]),
            ),
            const SizedBox(height: S.x3),
          ],
        ]),
      );
}

class AppDetail extends StatefulWidget {
  const AppDetail(this.a, {super.key});
  final AppInfo a;
  @override
  State<AppDetail> createState() => _AppDetailState();
}

class _AppDetailState extends State<AppDetail> {
  AppInfo get a => widget.a;

  /// Allowance per feed when this screen opened. Going back up to it is free; going above it asks you to wait.
  final Map<String, int> _base = {};

  String _title(Surface s) {
    _base.putIfAbsent(s.id, () => app.budgets[s.id]?.allowMinutes ?? 0);
    return '${s.label} allowance';
  }

  /// Minutes of daily allowance for a feed; 0 means none (always blocked).
  int _mins(Surface s) => app.budgets[s.id]?.allowMinutes ?? 0;

  /// Where the time starts when the user switches an allowance on. They set the real value right after.
  static const _startMinutes = 10;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          var n = 0;
          String ix() => (++n).toString().padLeft(2, '0');
          return Screen(
            title: a.name,
            subtitle: a.beta ? 'Beta. Newer rules, not tested on every phone or app version yet.' : 'Skip the endless part. Keep the rest.',
            children: [
              SectionLabel('Skip', index: ix()),
              Group([
                for (final s in a.surfaces)
                  Row2(
                    icon: Icons.block_rounded,
                    iconTone: C.red,
                    title: s.label,
                    sub: 'Unloop steps back when it opens',
                    trailing: Toggle(value: app.surfaceOn[s.id] ?? false, onChanged: (v) => setSurfaceChecked(context, s.id, v, s.label)),
                  ),
              ]),
              const SizedBox(height: S.x3),
              for (final s in a.surfaces) ...[
                Tile(
                  label: _title(s),
                  trailing: Toggle(
                    value: _mins(s) > 0,
                    onChanged: (v) async {
                      if (v) {
                        // Opening the door is a loosening: ask for the wait. The user picks the real time afterwards.
                        final base = _base[s.id] ?? 0;
                        if (_startMinutes > base && !await askFriction(context, title: 'Allow time in ${s.label}?', key: 'allowance')) return;
                        if (_startMinutes > base) _base[s.id] = _startMinutes;
                        await app.setBudget(s.id, _startMinutes);
                      } else {
                        await app.setBudget(s.id, 0);
                      }
                    },
                  ),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(
                      _mins(s) == 0
                          ? '${s.label} is blocked whenever it opens. No timer. Turn this on to allow some time each day.'
                          : 'Used today: ${dur(((app.budgets[s.id]?.usedSeconds ?? 0) / 60).round())} of ${dur(_mins(s))}. Counted only while ${s.label} is on screen, then it is blocked.',
                      style: T.small,
                    ),
                    if (_mins(s) > 0) ...[
                      const SizedBox(height: S.x4),
                      Row(children: [
                        Expanded(child: Text('TIME PER DAY', style: T.label)),
                        Stepper2(
                          value: _mins(s),
                          step: 5,
                          min: 5,
                          max: app.strict ? _mins(s) : 240,
                          onChanged: (v) async {
                            final base = _base[s.id] ?? 0;
                            if (v > base && !await askFriction(context, title: 'Allow more time?', key: 'allowance')) return;
                            if (v > base) _base[s.id] = v;
                            await app.setBudget(s.id, v);
                          },
                          format: (m) => dur(m).toUpperCase(),
                        ),
                      ]),
                    ],
                  ]),
                ),
                const SizedBox(height: S.x3),
              ],
              if (a.modes.isNotEmpty) ...[
                SectionLabel(a.modesTitle, index: ix()),
                Group([
                  for (final s in a.modes)
                    Row2(
                      icon: Icons.lock_outline_rounded,
                      title: s.label,
                      trailing: Toggle(value: app.surfaceOn[s.id] ?? false, onChanged: (v) => setSurfaceChecked(context, s.id, v, s.label)),
                    ),
                ]),
              ],
              if (a.extras.isNotEmpty) ...[
                SectionLabel(a.extrasTitle, index: ix()),
                Group([
                  for (final s in a.extras)
                    Row2(
                      icon: Icons.visibility_off_rounded,
                      title: s.label,
                      trailing: Toggle(value: app.surfaceOn[s.id] ?? false, onChanged: (v) => setSurfaceChecked(context, s.id, v, s.label)),
                    ),
                ]),
              ],
              if (a.sharedItem) ...[
              const SizedBox(height: S.x3),
              Group([
                Row2(
                  icon: Icons.share_rounded,
                  title: 'Let one shared video through',
                  sub: 'A ${a.surfaces.first.label.toLowerCase().replaceAll(RegExp(r's$'), '')} sent to you in a message opens once',
                  trailing: Toggle(value: app.allowShared, onChanged: app.setAllowShared),
                ),
              ]),
              ],
              SectionLabel('Always available', index: ix()),
              Tile(
                child: Wrap(spacing: S.x2, runSpacing: S.x2, children: [
                  for (final k in a.keeps)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      decoration: BoxDecoration(color: C.raised, borderRadius: R.pill),
                      child: Row(mainAxisSize: MainAxisSize.min, children: [
                        const Icon(Icons.check_rounded, size: 14, color: C.fg),
                        const SizedBox(width: 6),
                        Text(k, style: T.small.copyWith(color: C.text)),
                      ]),
                    ),
                ]),
              ),
              const SizedBox(height: S.x4),
              const Notice('Detection depends on the app’s own screens, so an app update can change how well it works. Learn mode in Settings helps fix that.'),
            ],
          );
        },
      );
}

// ---------------- Insights ----------------

class InsightsTab extends StatelessWidget {
  const InsightsTab({super.key});

  static const _letters = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final week = app.insightsRange == 1;
          final h = app.history;
          final todaySec = h.isEmpty ? 0 : h.last.seconds;
          final weekSec = h.fold<int>(0, (a, d) => a + d.seconds);
          final apps = app.usage.entries.where((e) => e.value >= 60).toList()..sort((a, b) => b.value.compareTo(a.value));
          return Screen(
            back: false,
            title: 'Insights',
            subtitle: 'Measured on your phone. Nothing leaves it.',
            children: [
              Segmented(options: const ['Today', 'Week'], index: app.insightsRange, onChanged: (v) { app.insightsRange = v; app.touch(); }),
              const SizedBox(height: S.x4),
              if (!app.perm.usageAccess) ...[
                Notice('Usage access is off, so screen time cannot be measured.', warn: true, action: 'Fix', onAction: app.native.openUsageAccessSettings),
                const SizedBox(height: S.x3),
              ],
              Tile(
                label: 'Screen time',
                index: '01',
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  DotText(dur(((week ? weekSec : todaySec) / 60).round()).toUpperCase(), pitch: 5),
                  if (week) ...[
                    const SizedBox(height: S.x5),
                    DotBars(
                      values: [for (final d in h) d.seconds / 60],
                      labels: [for (final d in h) _letters[DateTime.parse(d.date).weekday - 1]],
                      highlight: h.isEmpty ? null : h.length - 1,
                    ),
                  ],
                ]),
              ),
              if (week) ...[
                const SizedBox(height: S.x3),
                Tile(
                  label: 'Stepped back',
                  trailing: Tag('${app.blocksWeek} IN 7 DAYS'),
                  child: DotSparkline(values: [for (final d in h) d.blocks.toDouble()]),
                ),
              ],
              if (!week) ...[
                const SectionLabel('Apps today', index: '02'),
                if (apps.isEmpty)
                  const Tile(child: Text('Nothing measured yet today.', style: T.bodyMuted))
                else
                  Group([
                    for (final e in apps.take(8)) Row2(leading: AppIcon(e.key), title: app.appName(e.key), trailing: Text(dur((e.value / 60).round()).toUpperCase(), style: T.value.copyWith(fontSize: 16))),
                  ]),
              ],
              const SectionLabel('Stepped back', index: '03'),
              Group([
                Row2(icon: Icons.undo_rounded, title: week ? 'This week' : 'Today', sub: 'Measured', trailing: Text('${week ? app.blocksWeek : app.blocksToday}', style: T.value)),
                Row2(icon: Icons.all_inclusive_rounded, title: 'Since you started', sub: 'Measured', trailing: Text('${app.blocked}', style: T.value)),
              ]),
              const SizedBox(height: S.x4),
              const Notice('History starts the day Unloop first ran. Days before that are not guessed.'),
            ],
          );
        },
      );
}

// ---------------- Settings ----------------

class SettingsTab extends StatelessWidget {
  const SettingsTab({super.key});
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => Screen(back: false, title: 'Settings', children: [
          Group([
            Row2(
              icon: Icons.verified_user_outlined,
              title: 'Permission health',
              sub: app.perm.blockingActive ? 'All set' : 'Needs attention',
              trailing: Dot(app.perm.blockingActive ? C.fg : C.red),
              chevron: true,
              onTap: () => go(context, const HealthScreen()),
            ),
          ]),
          const SectionLabel('Look', index: '01'),
          Tile(
            label: 'Accent color',
            trailing: Tag(C.palette[app.accentIndex].$1, tone: C.fg),
            child: ColorPick(index: app.accentIndex, onChanged: app.setAccent),
          ),
          const SectionLabel('Protection', index: '02'),
          Group([
            Row2(icon: Icons.pause_circle_outline_rounded, title: 'Pause', sub: app.paused ? 'Paused until ${app.clock(app.pausedUntilMs)}' : 'Take a short break (5, 10 or 30 min)', chevron: true, onTap: () => go(context, const PauseScreen())),
            if (app.payment.available) Row2(icon: Icons.account_balance_wallet_outlined, title: 'Payment apps', sub: app.payment.on ? 'Unloop steps aside while you pay' : 'Stop payment apps complaining', chevron: true, onTap: () => go(context, const PaymentScreen())),
            Row2(icon: Icons.pin_outlined, title: 'Scroll counter', sub: app.autoKickN > 0 ? 'Home after ${app.autoKickN} swipes' : (app.counterOn ? 'Counting swipes' : 'Off'), chevron: true, onTap: () => go(context, const CounterScreen())),
            Row2(icon: Icons.notifications_none_rounded, title: 'Notifications', sub: 'Quiet the noisy ones', chevron: true, onTap: () => go(context, const NotifScreen())),
            Row2(icon: Icons.document_scanner_outlined, title: 'Learn mode', sub: 'Capture screens to fix or add rules', chevron: true, onTap: () => go(context, const LearnScreen())),
          ]),
          const SectionLabel('Strict mode', index: '03'),
          Tile(
            light: app.strict,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(app.strict ? 'ON UNTIL ${app.clock(app.strictUntilMs)}' : 'OFF', style: T.value.copyWith(color: app.strict ? C.ink : C.fg)),
              const SizedBox(height: S.x2),
              Text(
                'Locks your rules for 1, 8 or 24 hours. You can add or tighten rules, but not loosen, pause or turn anything off until it ends. It cannot be ended early. Turning Unloop off in Android Accessibility settings is still possible.',
                style: T.small.copyWith(color: app.strict ? C.ink : C.text2),
              ),
              const SizedBox(height: S.x4),
              Wrap(spacing: S.x2, runSpacing: S.x2, children: [
                for (final h in const [1, 8, 24])
                  Btn(app.strict ? 'EXTEND ${h}H' : '${h}H', kind: BtnKind.secondary, small: true, expand: false, onTap: () => app.setStrict(h * 60)),
              ]),
            ]),
          ),
          const SectionLabel('Your data', index: '04'),
          Group([
            Row2(icon: Icons.inventory_2_outlined, title: 'Backup and restore', sub: 'Export, import, delete', chevron: true, onTap: () => go(context, const BackupScreen())),
          ]),
          const SectionLabel('Unloop', index: '05'),
          Group([
            Row2(
              icon: Icons.slideshow_rounded,
              title: 'Replay introduction',
              chevron: true,
              onTap: () async {
                await app.native.setFlag('onboarded', false);
                app.onboarded = false;
                app.touch();
              },
            ),
            if (kDebugMode) Row2(icon: Icons.palette_outlined, title: 'Design system', sub: 'Components gallery', chevron: true, onTap: () => go(context, const GalleryPage())),
            Row2(icon: Icons.favorite_border_rounded, title: 'About Unloop', sub: 'Free, open source, private', chevron: true, onTap: () => go(context, const AboutScreen())),
          ]),
        ]),
      );
}
