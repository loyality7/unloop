import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter/widgets.dart';

import 'native_bridge.dart';
import 'ui/tokens.dart';

class Surface {
  const Surface(this.id, this.label);
  final String id, label;
}

class AppInfo {
  const AppInfo({
    this.beta = false,
    this.extras = const [],
    this.modes = const [],
    this.modesTitle = 'Modes',
    this.extrasTitle = 'Also hide',
    required this.pkg,
    required this.id,
    required this.name,
    required this.surfaces,
    required this.keeps,
    this.sharedItem = true,
  });
  final String pkg, id, name;
  final List<Surface> surfaces;

  /// Extra native surfaces (buttons, rows, tabs) the engine can hide or block. Off unless the user turns them on.
  final List<Surface> extras;
  final String extrasTitle;

  /// Whole-app modes (for example Messages only). Off unless the user turns them on.
  final List<Surface> modes;
  final String modesTitle;
  final List<String> keeps;

  /// Whether "let one shared video through" makes sense for this app.
  final bool sharedItem;
  final bool beta; // engine rules exist but are newer and less tested on real devices
}

const catalog = [
  AppInfo(
    pkg: 'com.instagram.android',
    id: 'instagram',
    name: 'Instagram',
    surfaces: [Surface('instagram_reels', 'Reels')],
    extras: [
      Surface('instagram_reels_tab', 'Reels button'),
      Surface('instagram_explore_tab', 'Explore tab'),
      Surface('instagram_stories', 'Stories row'),
      Surface('instagram_caught_up', "End of feed ('You're all caught up')"),
      Surface('instagram_comments', 'Comments (closes the panel when it opens)'),
      Surface('instagram_sponsored', 'Sponsored posts (ads)'),
      Surface('instagram_suggested', 'Suggested posts'),
      Surface('instagram_messages_tab', 'Messages button'),
    ],
    extrasTitle: 'Hide buttons, rows and panels',
    modes: [Surface('instagram_dms_only', 'Messages only')],
    modesTitle: 'Modes',
    keeps: ['Direct messages', 'Stories', 'Feed posts', 'Profiles', 'Search', 'Uploading', 'Comments'],
  ),
  AppInfo(
    pkg: 'com.google.android.youtube',
    id: 'youtube',
    name: 'YouTube',
    surfaces: [Surface('youtube_shorts', 'Shorts')],
    extras: [
      Surface('youtube_shorts_tab', 'Shorts button'),
      Surface('youtube_comments', 'Comments (closes the panel when it opens)'),
    ],
    extrasTitle: 'Hide buttons and panels',
    modes: [Surface('youtube_search_only', 'Search only (hides the Home feed)')],
    keeps: ['Regular videos', 'Subscriptions', 'Search', 'Playlists', 'Watch Later', 'Channels', 'Comments'],
  ),
  AppInfo(
    pkg: 'com.zhiliaoapp.musically',
    id: 'tiktok',
    beta: true,
    name: 'TikTok',
    surfaces: [Surface('tiktok_for_you', 'For You feed')],
    extras: [
      Surface('tiktok_following', 'Following tab'),
      Surface('tiktok_friends', 'Friends tab'),
      Surface('tiktok_local', 'Local tab'),
      Surface('tiktok_community', 'Community tab'),
      Surface('tiktok_stem', 'STEM tab'),
      Surface('tiktok_shop', 'Shop tab'),
      Surface('tiktok_live', 'LIVE tab'),
    ],
    extrasTitle: 'Also block these tabs',
    keeps: ['Following feed', 'Search', 'Messages', 'Profiles', 'Uploading'],
  ),
  AppInfo(
    pkg: 'com.snapchat.android',
    id: 'snapchat',
    beta: true,
    name: 'Snapchat',
    surfaces: [Surface('snapchat_spotlight', 'Spotlight')],
    extras: [Surface('snapchat_discover', 'Discover and sponsored content')],
    extrasTitle: 'Also block',
    keeps: ['Chats', 'Snaps', 'Stories', 'Friends'],
  ),
  AppInfo(
    pkg: 'com.facebook.katana',
    id: 'facebook',
    beta: true,
    name: 'Facebook',
    surfaces: [Surface('facebook_reels', 'Reels')],
    extras: [Surface('facebook_suggested', 'Suggested and sponsored posts')],
    extrasTitle: 'Hide',
    modes: [
      Surface('facebook_marketplace_only', 'Marketplace only'),
      Surface('facebook_groups_feed', "Groups: 'Your groups' only"),
    ],
    modesTitle: 'Modes',
    keeps: ['Messenger', 'Groups', 'Pages', 'Marketplace', 'Feed'],
  ),
  AppInfo(
    pkg: 'com.twitter.android',
    id: 'x',
    beta: true,
    name: 'X',
    surfaces: [Surface('x_for_you', "'For you' timeline")],
    extras: [Surface('x_explore', 'Explore tab')],
    extrasTitle: 'Also block',
    modes: [Surface('x_dms_only', 'Messages only')],
    keeps: ['Following timeline', 'Messages', 'Search', 'Profiles', 'Posting'],
    sharedItem: false,
  ),
];

/// Several apps sharing one daily limit.
class AppGroup {
  AppGroup(this.id, this.name, this.pkgs, this.minutes);
  final String id;
  String name;
  final Set<String> pkgs;
  int minutes;
}

class Limit {
  Limit(this.pkg, this.name, this.minutes, this.usedMinutes, {this.warn = 80});
  final String pkg, name;
  int minutes;
  int usedMinutes;

  /// Percent of the limit at which a calm heads-up notification is posted.
  int warn;
}

/// Apps whose notifications the filter can quiet.
const notifCandidates = {
  'com.instagram.android': 'Instagram',
  'com.zhiliaoapp.musically': 'TikTok',
  'com.snapchat.android': 'Snapchat',
  'com.facebook.katana': 'Facebook',
  'com.twitter.android': 'X',
  'com.reddit.frontpage': 'Reddit',
};

class Schedule {
  Schedule(this.name, this.start, this.end, this.days, this.on);
  String name;
  int start, end; // minutes of day
  List<bool> days;
  bool on;
}

/// Single app-wide state. Everything here is read from and saved to the native side; nothing is sample data.
class AppState extends ChangeNotifier {
  final native = NativeBridge();
  bool loaded = false, onboarded = false;
  PermissionState perm = PermissionState.unknown;
  final Map<String, bool> surfaceOn = {};
  int blocked = 0;

  final limits = <Limit>[];
  final schedules = <Schedule>[];
  final scheduledPkgs = <String>{};
  final groups = <AppGroup>[];
  PaymentState payment = const PaymentState();
  bool counterOn = true;
  int autoKickN = 0;
  List<InstalledApp> installed = [];
  NotifConfig notif = const NotifConfig();
  bool notifAccess = false, allowShared = true, masterOn = true;
  List<DayStat> history = [];
  Map<String, int> usage = {};
  Map<String, int> diag = {};
  int pausedUntilMs = 0;
  int strictUntilMs = 0;
  Map<String, Budget> budgets = {};
  LearnState learnState = const LearnState('', 0, true);
  List<Capture> captures = [];
  bool learnText = false;

  /// Short message shown over every screen (errors, strict-mode refusals).
  String? toast;
  Timer? _toastTimer;

  int get _now => DateTime.now().millisecondsSinceEpoch;
  bool get paused => pausedUntilMs > _now;
  bool get strict => strictUntilMs > _now;
  int get blocksToday => history.isEmpty ? 0 : history.last.blocks;
  int get blocksWeek => history.fold(0, (a, d) => a + d.blocks);

  final Map<String, Uint8List> _icons = {};
  final Set<String> _iconAsked = {};

  /// Cached launcher icon, fetched once in the background. Null until it arrives (or when not installed).
  Uint8List? iconOf(String pkg) {
    if (_iconAsked.add(pkg)) _loadIcon(pkg);
    return _icons[pkg];
  }

  Future<void> _loadIcon(String pkg) async {
    final bytes = await native.appIcon(pkg);
    if (bytes == null) return;
    _icons[pkg] = bytes;
    notifyListeners();
  }

  /// Whether [pkg] is on this phone. While the app list is still loading (or unavailable) nothing is hidden.
  bool isInstalled(String pkg) => installed.isEmpty || installed.any((i) => i.pkg == pkg);

  /// Catalog apps that are on this phone.
  List<AppInfo> get installedCatalog => [for (final a in catalog) if (isInstalled(a.pkg)) a];

  /// Notification-filter apps that are installed, plus any already switched on (so a saved choice never vanishes).
  List<MapEntry<String, String>> get notifApps =>
      [for (final e in notifCandidates.entries) if (isInstalled(e.key) || notif.packages.contains(e.key)) e];

  String appName(String pkg) =>
      catalog.where((c) => c.pkg == pkg).firstOrNull?.name ?? installed.where((a) => a.pkg == pkg).firstOrNull?.label ?? pkg;

  /// "HH:mm" local time for an epoch-ms value.
  String clock(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return hm(d.hour * 60 + d.minute);
  }

  // ---- view state ----
  int insightsRange = 0;
  int accentIndex = 0;

  void say(String m) {
    toast = m;
    notifyListeners();
    _toastTimer?.cancel();
    _toastTimer = Timer(const Duration(seconds: 4), () {
      toast = null;
      notifyListeners();
    });
  }

  /// Runs a native write. A strict-mode refusal or any failure is shown to the user and the screen is re-read from
  /// native, so the UI never keeps a value that was not saved.
  Future<void> _guard(Future<void> Function() f) async {
    try {
      await f();
    } on PlatformException catch (e) {
      if ((e.message ?? '').contains('strict_mode')) {
        say('Strict mode is on until ${clock(strictUntilMs)}. Protection can only get tighter.');
      } else {
        say('Could not save that. ${e.message ?? ''}'.trim());
      }
      await refresh();
      await loadRules();
    }
  }

  Future<void> load() async {
    onboarded = await native.flag('onboarded');
    accentIndex = (await native.intPref('accent', 0)).clamp(0, C.palette.length - 1);
    C.signal = C.palette[accentIndex].$2;
    await refresh();
    if (!masterOn) await setMaster(true); // the master switch is gone from the app: never leave it off
    await loadRules();
    loaded = true;
    notifyListeners();
  }

  Future<void> refresh() async {
    perm = await native.permissionState();
    blocked = await native.blockCount();
    for (final a in catalog) {
      for (final s in [...a.surfaces, ...a.extras, ...a.modes]) {
        surfaceOn[s.id] = await native.surfaceEnabled(s.id);
      }
    }
    notifAccess = (await native.healthStatus()).notificationListener;
    pausedUntilMs = await native.pausedUntil();
    masterOn = await native.masterEnabled();
    strictUntilMs = await native.strictUntil();
    payment = await native.payment();
    if (!perm.accessibilityEnabled && !payment.steppedAside && await native.flag('a11y_consent')) {
      await native.setFlag('a11y_consent', false); // turned off: the next time it is added, ask again
    }
    final c = await native.counter();
    counterOn = c.$1;
    autoKickN = c.$2;
    diag = await native.diagnostics();
    budgets = await native.budgets();
    learnState = await native.learn();
    await refreshUsage();
  }

  static const _days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
  static int _mins(String hhmm) {
    final p = hhmm.split(':');
    return int.parse(p[0]) * 60 + int.parse(p[1]);
  }

  Future<void> loadRules() async {
    installed = await native.installedApps();
    final used = await native.usageToday();
    limits
      ..clear()
      ..addAll([
        for (final r in await native.limits())
          Limit(
            r.target,
            appName(r.target),
            r.dailySeconds ~/ 60,
            (used[r.target] ?? 0) ~/ 60,
            warn: r.warnPercents.isEmpty ? 80 : r.warnPercents.reduce((a, b) => a < b ? a : b),
          ),
      ]);
    schedules
      ..clear()
      ..addAll([
        for (final r in await native.schedules())
          Schedule(r.id, _mins(r.start), _mins(r.end), [for (final d in _days) r.days.contains(d)], r.enabled),
      ]);
    scheduledPkgs
      ..clear()
      ..addAll(await native.scheduledApps());
    notif = await native.notifConfig();
    allowShared = await native.allowShared();
    groups
      ..clear()
      ..addAll(_parseGroups(await native.groupsJson()));
    notifyListeners();
  }

  static List<AppGroup> _parseGroups(String json) {
    try {
      return [
        for (final e in jsonDecode(json) as List)
          AppGroup(
            e['id'] as String,
            e['name'] as String? ?? 'Group',
            ((e['pkgs'] as List?) ?? const []).cast<String>().toSet(),
            ((e['dailySeconds'] as num).toInt()) ~/ 60,
          ),
      ];
    } catch (_) {
      return [];
    }
  }

  AppGroup newGroup() {
    final g = AppGroup('g${DateTime.now().millisecondsSinceEpoch}', 'Group ${groups.length + 1}', {}, 60);
    groups.add(g);
    saveGroups();
    return g;
  }

  Future<void> saveGroups() async {
    notifyListeners();
    await _guard(() => native.setGroupsJson(jsonEncode([
          for (final g in groups) {'id': g.id, 'name': g.name, 'pkgs': g.pkgs.toList(), 'dailySeconds': g.minutes * 60},
        ])));
  }

  Future<void> removeGroup(AppGroup g) async {
    groups.remove(g);
    await saveGroups();
  }

  Future<void> setPayment(bool on) async {
    try {
      await native.setPayment(on);
    } on PlatformException catch (e) {
      say(e.message ?? 'Could not change that.');
    }
    payment = await native.payment();
    notifyListeners();
  }

  Future<void> setCounterOn(bool on) async {
    counterOn = on;
    notifyListeners();
    await _guard(() => native.setCounter(on));
  }

  /// Send the user Home after [n] swipes in an endless feed; 0 turns it off.
  Future<void> setAutoKick(int n) async {
    autoKickN = n;
    notifyListeners();
    await _guard(() => native.setAutoKick(n));
  }

  Future<void> refreshUsage() async {
    usage = await native.usageToday();
    history = await native.usageHistory(7);
    for (final l in limits) {
      l.usedMinutes = (usage[l.pkg] ?? 0) ~/ 60;
    }
    notifyListeners();
  }

  Future<void> saveLimits() async {
    notifyListeners();
    await _guard(() => native.setLimits([for (final l in limits) LimitRule(l.pkg, l.minutes * 60, warnPercents: [l.warn])]));
  }

  Future<void> saveSchedules() async {
    notifyListeners();
    await _guard(() => native.setSchedules([
          for (final s in schedules)
            ScheduleRule(s.name, s.on, [for (var i = 0; i < 7; i++) if (s.days[i]) _days[i]], hm(s.start), hm(s.end)),
        ]));
  }

  Future<void> saveNotif(NotifConfig c) async {
    notif = c;
    notifyListeners();
    await native.setNotifConfig(c);
  }

  Future<void> setAllowShared(bool on) async {
    allowShared = on;
    notifyListeners();
    await native.setAllowShared(on);
  }

  Future<void> setScheduledApp(String pkg, bool on) async {
    on ? scheduledPkgs.add(pkg) : scheduledPkgs.remove(pkg);
    notifyListeners();
    await _guard(() => native.setScheduledApps(scheduledPkgs.toList()));
  }

  /// Daily allowance for a surface in minutes; 0 = always blocked.
  Future<void> setBudget(String surface, int minutes) async {
    final old = budgets[surface];
    budgets[surface] = Budget(minutes, old?.usedSeconds ?? 0);
    notifyListeners();
    await _guard(() => native.setBudget(surface, minutes));
    budgets = await native.budgets();
    notifyListeners();
  }

  Future<void> loadLearn() async {
    learnState = await native.learn();
    captures = await native.captures();
    learnText = await native.flag('learn_text');
    notifyListeners();
  }

  /// Opt-in: keep on-screen text in captures (to write label-based rules such as the Sponsored label).
  Future<void> setLearnText(bool on) async {
    learnText = on;
    notifyListeners();
    await native.setFlag('learn_text', on);
  }

  /// Empty [pkg] turns Learn mode off.
  Future<void> setLearn(String pkg) async {
    try {
      await native.setLearn(pkg);
    } on PlatformException catch (e) {
      say(e.message ?? 'Could not start Learn mode.');
    }
    await loadLearn();
  }

  Future<void> setMaster(bool on) async {
    masterOn = on;
    notifyListeners();
    await _guard(() => native.setMasterEnabled(on));
  }

  Future<void> setStrict(int minutes) async {
    await _guard(() async {
      strictUntilMs = await native.setStrict(minutes);
    });
    notifyListeners();
  }

  Future<void> pause(int minutes) async {
    await _guard(() async {
      await native.pauseFor(minutes);
      pausedUntilMs = await native.pausedUntil();
    });
    notifyListeners();
  }

  Future<void> resume() async {
    await native.resumeProtection();
    pausedUntilMs = 0;
    notifyListeners();
  }

  /// Delete every local rule, setting, counter and history, then start again from onboarding.
  Future<void> wipeAll() async {
    try {
      await native.wipeAll();
    } on PlatformException catch (e) {
      say((e.message ?? '').contains('strict_mode')
          ? 'Strict mode is on until ${clock(strictUntilMs)}. Nothing can be erased yet.'
          : 'Could not erase. ${e.message ?? ''}'.trim());
      return;
    }
    limits.clear();
    schedules.clear();
    scheduledPkgs.clear();
    groups.clear();
    history = [];
    usage = {};
    captures = [];
    notif = const NotifConfig();
    allowShared = true;
    onboarded = false;
    accentIndex = 0;
    C.signal = C.palette[0].$2;
    await refresh();
    await loadRules();
  }

  Future<void> finishOnboarding() async {
    onboarded = true;
    await native.setFlag('onboarded', true);
    notifyListeners();
  }

  Future<void> setSurface(String id, bool on) async {
    surfaceOn[id] = on;
    notifyListeners();
    await _guard(() => native.setSurfaceEnabled(id, on));
  }

  Future<void> setAccent(int i) async {
    accentIndex = i;
    C.signal = C.palette[i].$2;
    _rebuildAll(); // const widgets and painters read C.signal, so force a full rebuild (keeps state)
    notifyListeners();
    await native.setIntPref('accent', i);
  }

  void _rebuildAll() {
    void visit(Element e) {
      e.markNeedsBuild();
      e.visitChildren(visit);
    }
    WidgetsBinding.instance.rootElement?.visitChildren(visit);
  }

  void touch() => notifyListeners();
}

final app = AppState();

String hm(int minutes) {
  final h = (minutes ~/ 60) % 24, m = minutes % 60;
  return '${h.toString().padLeft(2, '0')}:${m.toString().padLeft(2, '0')}';
}

String dur(int minutes) {
  final h = minutes ~/ 60, m = minutes % 60;
  if (h == 0) return '${m}m';
  return m == 0 ? '${h}h' : '${h}h ${m}m';
}
