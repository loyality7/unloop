import 'dart:convert';

import 'package:flutter/services.dart';

class PermissionState {
  const PermissionState({
    required this.accessibilityEnabled,
    required this.accessibilityConnected,
    required this.usageAccess,
  });
  final bool accessibilityEnabled;
  final bool accessibilityConnected;
  final bool usageAccess;

  /// Active only when enabled in Settings AND the service is actually connected.
  bool get blockingActive => accessibilityEnabled && accessibilityConnected;

  static const unknown = PermissionState(
      accessibilityEnabled: false, accessibilityConnected: false, usageAccess: false);
}

/// Thin Dart side of the native Android engine. Keep logic native; this only relays.
class NativeBridge {
  static const _ch = MethodChannel('unloop/native');

  Future<PermissionState> permissionState() async {
    try {
      final m = await _ch.invokeMapMethod<String, bool>('permissionState');
      return PermissionState(
        accessibilityEnabled: m?['accessibilityEnabled'] ?? false,
        accessibilityConnected: m?['accessibilityConnected'] ?? false,
        usageAccess: m?['usageAccess'] ?? false,
      );
    } on MissingPluginException {
      return PermissionState.unknown; // non-Android platforms
    }
  }

  Future<void> openAccessibilitySettings() => _ch.invokeMethod('openAccessibilitySettings');
  Future<void> openUsageAccessSettings() => _ch.invokeMethod('openUsageAccessSettings');

  Future<bool> surfaceEnabled(String id) async {
    try {
      return await _ch.invokeMethod<bool>('getSurfaceEnabled', {'id': id}) ?? true;
    } on MissingPluginException {
      return true;
    }
  }
  Future<void> setSurfaceEnabled(String id, bool on) async {
    try {
      await _ch.invokeMethod('setSurfaceEnabled', {'id': id, 'on': on});
    } on MissingPluginException {
      // non-Android: nothing to persist yet
    }
  }
  Future<bool> flag(String k) async {
    try {
      return await _ch.invokeMethod<bool>('getFlag', {'k': k}) ?? false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<void> setFlag(String k, bool v) async {
    try {
      await _ch.invokeMethod('setFlag', {'k': k, 'v': v});
    } on MissingPluginException {
      // non-Android: nothing to persist yet
    }
  }

  Future<int> intPref(String k, int d) async {
    try {
      return await _ch.invokeMethod<int>('getInt', {'k': k, 'd': d}) ?? d;
    } on MissingPluginException {
      return d;
    }
  }

  Future<void> setIntPref(String k, int v) async {
    try {
      await _ch.invokeMethod('setInt', {'k': k, 'v': v});
    } on MissingPluginException {
      // non-Android: nothing to persist yet
    }
  }

  Future<int> blockCount() async {
    try {
      return await _ch.invokeMethod<int>('blockCount') ?? 0;
    } on MissingPluginException {
      return 0;
    }
  }

  Future<T> _safe<T>(Future<T?> Function() call, T fallback) async {
    try {
      return await call() ?? fallback;
    } on MissingPluginException {
      return fallback; // non-Android platforms
    }
  }

  /// Pause all protection (blocking, covers, limits) for [minutes]; resumes by itself. Tile does the same natively.
  Future<void> pauseFor(int minutes) => _safe<Object?>(() => _ch.invokeMethod('pauseFor', {'minutes': minutes}), null);
  Future<void> resumeProtection() => _safe<Object?>(() => _ch.invokeMethod('resumeProtection'), null);
  Future<bool> isPaused() => _safe(() => _ch.invokeMethod<bool>('isPaused'), false);

  /// Epoch milliseconds when the pause ends; 0 when not paused.
  Future<int> pausedUntil() => _safe(() => _ch.invokeMethod<int>('pausedUntil'), 0);

  Future<bool> masterEnabled() => _safe(() => _ch.invokeMethod<bool>('getMasterEnabled'), true);
  Future<void> setMasterEnabled(bool on) =>
      _safe<Object?>(() => _ch.invokeMethod('setMasterEnabled', {'on': on}), null);
  Future<List<String>> listSurfaces() =>
      _safe(() => _ch.invokeListMethod<String>('listSurfaces'), <String>[]);

  Future<List<LimitRule>> limits() async =>
      parseLimits(await _safe(() => _ch.invokeMethod<String>('getLimits'), '[]'));
  Future<void> setLimits(List<LimitRule> l) =>
      _safe<Object?>(() => _ch.invokeMethod('setLimits', {'json': limitsToJson(l)}), null);

  Future<List<ScheduleRule>> schedules() async =>
      parseSchedules(await _safe(() => _ch.invokeMethod<String>('getSchedules'), '[]'));
  Future<void> setSchedules(List<ScheduleRule> s) =>
      _safe<Object?>(() => _ch.invokeMethod('setSchedules', {'json': schedulesToJson(s)}), null);

  /// Packages that schedules (quiet hours) apply to even without a limit rule.
  /// Effective enforcement set = packages with a limit rule UNION this list.
  Future<List<String>> scheduledApps() =>
      _safe(() => _ch.invokeListMethod<String>('getScheduledApps'), <String>[]);
  Future<void> setScheduledApps(List<String> pkgs) =>
      _safe<Object?>(() => _ch.invokeMethod('setScheduledApps', {'json': scheduledAppsToJson(pkgs)}), null);

  /// Seconds used today per package.
  Future<Map<String, int>> usageToday() => _safe(() async {
        final m = await _ch.invokeMapMethod<String, Object?>('usageToday');
        return m?.map((k, v) => MapEntry(k, (v as num).toInt()));
      }, <String, int>{});

  Future<HealthStatus> healthStatus() async =>
      HealthStatus.parse(await _safe(() => _ch.invokeMethod<String>('healthStatus'), '{}'));

  Future<Map<String, int>> diagnostics() async =>
      parseCounters(await _safe(() => _ch.invokeMethod<String>('diagnostics'), '{}'));

  Future<String> exportBackup() => _safe(() => _ch.invokeMethod<String>('exportBackup'), '');
  Future<ImportOutcome> importBackup(String json) async {
    try {
      final m = await _ch.invokeMapMethod<String, Object?>('importBackup', {'json': json});
      return ImportOutcome.fromMap(m ?? const {});
    } on MissingPluginException {
      return const ImportOutcome(ok: false, error: 'unavailable');
    }
  }

  Future<List<InstalledApp>> installedApps() async {
    final l = await _safe(() => _ch.invokeMethod<List<Object?>>('installedApps'), <Object?>[]);
    return [for (final e in l) InstalledApp((e as Map)['pkg'] as String, e['label'] as String)];
  }

  /// The app's launcher icon (PNG), or null when it is not installed or protected.
  Future<Uint8List?> appIcon(String pkg) async {
    try {
      return await _ch.invokeMethod<Uint8List>('appIcon', {'pkg': pkg, 'px': 96});
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  Future<PaymentState> payment() async {
    final m = await _safe(() => _ch.invokeMapMethod<String, Object?>('getPayment'), <String, Object?>{});
    return PaymentState(
      available: m['available'] as bool? ?? false,
      strictBounce: m['strictBounce'] as bool? ?? false,
      granted: m['granted'] as bool? ?? false,
      usage: m['usage'] as bool? ?? false,
      on: m['on'] as bool? ?? false,
      pkg: m['pkg'] as String? ?? 'com.gainandshine.unloop',
      steppedAside: ((m['asideSince'] as num?) ?? 0) > 0,
      apps: [for (final e in (m['apps'] as List?) ?? const []) InstalledApp((e as Map)['pkg'] as String, e['label'] as String)],
    );
  }

  /// Throws PlatformException `needs_setup` when the permission or Usage access is missing.
  Future<void> setPayment(bool on) => _safe<Object?>(() => _ch.invokeMethod('setPayment', {'on': on}), null);
  Future<bool> restoreAside() => _safe(() => _ch.invokeMethod<bool>('restoreAside'), false);

  Future<String> groupsJson() => _safe(() => _ch.invokeMethod<String>('getGroups'), '[]');
  Future<void> setGroupsJson(String json) => _safe<Object?>(() => _ch.invokeMethod('setGroups', {'json': json}), null);

  /// (counter shown, auto-kick after N swipes; 0 = off)
  Future<(bool, int)> counter() async {
    final m = await _safe(() => _ch.invokeMapMethod<String, Object?>('getCounter'), <String, Object?>{});
    return (m['on'] as bool? ?? true, (m['kick'] as num?)?.toInt() ?? 0);
  }

  Future<void> setCounter(bool on) => _safe<Object?>(() => _ch.invokeMethod('setCounter', {'on': on}), null);
  Future<void> setAutoKick(int n) => _safe<Object?>(() => _ch.invokeMethod('setAutoKick', {'n': n}), null);

  Future<NotifConfig> notifConfig() async =>
      NotifConfig.parse(await _safe(() => _ch.invokeMethod<String>('getNotifConfig'), '{}'));
  Future<void> setNotifConfig(NotifConfig c) =>
      _safe<Object?>(() => _ch.invokeMethod('setNotifConfig', {'json': c.toJson()}), null);

  Future<List<DayStat>> usageHistory(int days) async {
    final l = await _safe(() => _ch.invokeMethod<List<Object?>>('usageHistory', {'days': days}), <Object?>[]);
    return [
      for (final e in l)
        DayStat((e as Map)['date'] as String, (e['seconds'] as num).toInt(), (e['blocks'] as num).toInt()),
    ];
  }

  Future<void> wipeAll() => _safe<Object?>(() => _ch.invokeMethod('wipeAll'), null);

  /// surface id -> allowance minutes and seconds used today.
  Future<Map<String, Budget>> budgets() async {
    final m = await _safe(() => _ch.invokeMapMethod<String, Object?>('getBudgets'), <String, Object?>{});
    return {
      for (final e in m.entries)
        e.key: Budget(((e.value as List)[0] as num).toInt(), ((e.value as List)[1] as num).toInt()),
    };
  }

  Future<void> setBudget(String surface, int minutes) =>
      _safe<Object?>(() => _ch.invokeMethod('setBudget', {'surface': surface, 'minutes': minutes}), null);

  Future<LearnState> learn() async {
    final m = await _safe(() => _ch.invokeMapMethod<String, Object?>('getLearn'), <String, Object?>{});
    return LearnState(m['pkg'] as String? ?? '', (m['count'] as num?)?.toInt() ?? 0, m['canPost'] as bool? ?? true);
  }

  /// Empty [pkg] turns Learn mode off. Throws PlatformException (`protected`, `notifications_off`) when refused.
  Future<void> setLearn(String pkg) => _safe<Object?>(() => _ch.invokeMethod('setLearn', {'pkg': pkg}), null);

  Future<List<Capture>> captures() async {
    final l = await _safe(() => _ch.invokeMethod<List<Object?>>('listCaptures'), <Object?>[]);
    return [
      for (final e in l)
        Capture((e as Map)['pkg'] as String, (e['at'] as num).toInt(), (e['bytes'] as num).toInt()),
    ];
  }

  Future<String> exportCaptures() => _safe(() => _ch.invokeMethod<String>('exportCaptures'), '');
  Future<void> deleteCaptures() => _safe<Object?>(() => _ch.invokeMethod('deleteCaptures'), null);

  Future<int> strictUntil() => _safe(() => _ch.invokeMethod<int>('getStrictUntil'), 0);

  /// Starts or extends strict mode; returns when it ends (epoch ms). Ending it early is refused natively.
  Future<int> setStrict(int minutes) => _safe(() => _ch.invokeMethod<int>('setStrict', {'minutes': minutes}), 0);

  Future<bool> allowShared() => _safe(() => _ch.invokeMethod<bool>('getAllowShared'), true);
  Future<void> setAllowShared(bool on) => _safe<Object?>(() => _ch.invokeMethod('setAllowShared', {'on': on}), null);

  Future<void> openNotificationAccessSettings() =>
      _safe<Object?>(() => _ch.invokeMethod('openNotificationAccessSettings'), null);
  Future<bool> requestNotificationPermission() =>
      _safe(() => _ch.invokeMethod<bool>('requestNotificationPermission'), false);

}

class LimitRule {
  const LimitRule(this.target, this.dailySeconds, {this.warnPercents = const [80]});
  final String target;
  final int dailySeconds;
  final List<int> warnPercents;
}

class ScheduleRule {
  const ScheduleRule(this.id, this.enabled, this.days, this.start, this.end);
  final String id;
  final bool enabled;

  /// Upper-case English names, MONDAY..SUNDAY (java.time.DayOfWeek).
  final List<String> days;

  /// "HH:mm" local wall-clock; end < start means overnight.
  final String start;
  final String end;
}

/// JSON array of unique, non-blank package names (wire format of `setScheduledApps`).
String scheduledAppsToJson(List<String> p) =>
    jsonEncode(p.where((e) => e.trim().isNotEmpty).toSet().toList());

List<LimitRule> parseLimits(String json) => (jsonDecode(json) as List)
    .map((e) => LimitRule(e['target'] as String, (e['dailySeconds'] as num).toInt(),
        warnPercents: ((e['warnPercents'] as List?) ?? const [80]).map((p) => (p as num).toInt()).toList()))
    .toList();

String limitsToJson(List<LimitRule> l) => jsonEncode([
      for (final r in l) {'target': r.target, 'dailySeconds': r.dailySeconds, 'warnPercents': r.warnPercents}
    ]);

List<ScheduleRule> parseSchedules(String json) => (jsonDecode(json) as List)
    .map((e) => ScheduleRule(e['id'] as String, e['enabled'] as bool,
        (e['days'] as List).cast<String>(), e['start'] as String, e['end'] as String))
    .toList();

String schedulesToJson(List<ScheduleRule> s) => jsonEncode([
      for (final r in s) {'id': r.id, 'enabled': r.enabled, 'days': r.days, 'start': r.start, 'end': r.end}
    ]);

Map<String, int> parseCounters(String json) =>
    (jsonDecode(json) as Map).map((k, v) => MapEntry(k as String, (v as num).toInt()));

class HealthStatus {
  const HealthStatus({
    this.healthy = true,
    this.faults = const [],
    this.accessibilityEnabled = false,
    this.accessibilityConnected = false,
    this.usageAccess = false,
    this.notificationListener = false,
    this.notificationsAllowed = false,
  });
  final bool healthy;

  /// FaultKind names, e.g. ACCESSIBILITY_CRASHED.
  final List<String> faults;
  final bool accessibilityEnabled, accessibilityConnected, usageAccess, notificationListener, notificationsAllowed;

  factory HealthStatus.parse(String json) {
    final m = jsonDecode(json) as Map;
    if (m.isEmpty) return const HealthStatus();
    return HealthStatus(
      healthy: m['healthy'] as bool? ?? true,
      faults: ((m['faults'] as List?) ?? const []).cast<String>(),
      accessibilityEnabled: m['accessibilityEnabled'] as bool? ?? false,
      accessibilityConnected: m['accessibilityConnected'] as bool? ?? false,
      usageAccess: m['usageAccess'] as bool? ?? false,
      notificationListener: m['notificationListener'] as bool? ?? false,
      notificationsAllowed: m['notificationsAllowed'] as bool? ?? false,
    );
  }
}

class ImportOutcome {
  const ImportOutcome({required this.ok, this.schedules = 0, this.limits = 0, this.settings = 0, this.skipped = const [], this.error});
  final bool ok;
  final int schedules, limits, settings;
  final List<String> skipped;
  final String? error;

  factory ImportOutcome.fromMap(Map<String, Object?> m) => ImportOutcome(
        ok: m['ok'] as bool? ?? false,
        schedules: (m['schedules'] as num?)?.toInt() ?? 0,
        limits: (m['limits'] as num?)?.toInt() ?? 0,
        settings: (m['settings'] as num?)?.toInt() ?? 0,
        skipped: ((m['skipped'] as List?) ?? const []).cast<String>(),
        error: m['error'] as String?,
      );
}


class InstalledApp {
  const InstalledApp(this.pkg, this.label);
  final String pkg, label;
}

class NotifConfig {
  const NotifConfig({this.enabled = false, this.packages = const {}, this.snoozeMinutes = 30, this.allowlist = const {}, this.scheduleOnly = false});
  final bool enabled;

  /// Snooze only while one of the user's schedules is running.
  final bool scheduleOnly;
  final Set<String> packages;
  final int snoozeMinutes;
  final Set<String> allowlist;

  NotifConfig copyWith({bool? enabled, Set<String>? packages, int? snoozeMinutes, Set<String>? allowlist, bool? scheduleOnly}) => NotifConfig(
        enabled: enabled ?? this.enabled,
        packages: packages ?? this.packages,
        snoozeMinutes: snoozeMinutes ?? this.snoozeMinutes,
        allowlist: allowlist ?? this.allowlist,
        scheduleOnly: scheduleOnly ?? this.scheduleOnly,
      );

  factory NotifConfig.parse(String json) {
    final m = jsonDecode(json) as Map;
    Set<String> set(String k) => ((m[k] as List?) ?? const []).cast<String>().toSet();
    return NotifConfig(
      enabled: m['enabled'] as bool? ?? false,
      packages: set('packages'),
      snoozeMinutes: (((m['snoozeMs'] as num?) ?? 1800000) ~/ 60000).toInt(),
      allowlist: set('allowlist'),
      scheduleOnly: m['scheduleOnly'] as bool? ?? false,
    );
  }

  String toJson() => jsonEncode({
        'enabled': enabled,
        'packages': packages.toList(),
        'snoozeMs': snoozeMinutes * 60000,
        'allowlist': allowlist.toList(),
        'scheduleOnly': scheduleOnly,
      });
}

class DayStat {
  const DayStat(this.date, this.seconds, this.blocks);
  final String date;
  final int seconds, blocks;
}

class Budget {
  const Budget(this.allowMinutes, this.usedSeconds);
  final int allowMinutes, usedSeconds;
}

class LearnState {
  const LearnState(this.pkg, this.count, this.canPost);
  final String pkg;
  final int count;
  final bool canPost;
}

class Capture {
  const Capture(this.pkg, this.atMs, this.bytes);
  final String pkg;
  final int atMs, bytes;
}

class PaymentState {
  const PaymentState({this.available = false, this.strictBounce = false, this.granted = false, this.usage = false, this.on = false, this.pkg = 'com.gainandshine.unloop', this.steppedAside = false, this.apps = const []});
  final bool granted, usage, on, steppedAside;

  /// False in the Google Play build, which does not contain the step-aside feature.
  final bool available;

  /// False in the Google Play build: strict mode never interferes with the Android settings page there.
  final bool strictBounce;
  final String pkg;
  final List<InstalledApp> apps;

  String get command => 'adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS';
}
