import 'package:flutter_test/flutter_test.dart';
import 'package:unloop/native_bridge.dart';
import 'package:unloop/state.dart';

void main() {
  tearDown(() => app.installed = []);

  test('only installed apps are listed; nothing is hidden while the app list is unknown', () {
    app.installed = [];
    expect(app.installedCatalog.length, catalog.length);
    app.installed = [const InstalledApp('com.instagram.android', 'Instagram'), const InstalledApp('com.twitter.android', 'X')];
    expect(app.installedCatalog.map((a) => a.id), ['instagram', 'x']);
    expect(app.isInstalled('com.snapchat.android'), isFalse);
  });

  test('every switchable surface has a unique id', () {
    final ids = [for (final a in catalog) ...[...a.surfaces, ...a.extras, ...a.modes].map((s) => s.id)];
    expect(ids.toSet().length, ids.length);
    expect(ids, containsAll(['x_for_you', 'x_dms_only', 'youtube_search_only', 'instagram_sponsored', 'facebook_suggested']));
  });

  test('X does not offer the shared-video allowance', () {
    expect(catalog.firstWhere((a) => a.id == 'x').sharedItem, isFalse);
    expect(catalog.firstWhere((a) => a.id == 'instagram').sharedItem, isTrue);
  });

  test('notification config keeps the schedules-only choice and defaults it to off', () {
    final c = const NotifConfig(enabled: true, scheduleOnly: true);
    expect(NotifConfig.parse(c.toJson()).scheduleOnly, isTrue);
    expect(NotifConfig.parse('{}').scheduleOnly, isFalse);
    expect(c.copyWith(enabled: false).scheduleOnly, isTrue, reason: 'other edits must not reset it');
  });

  test('the new Instagram switches exist exactly once', () {
    final ids = [for (final a in catalog) ...[...a.surfaces, ...a.extras, ...a.modes].map((s) => s.id)];
    expect(ids, containsAll(['instagram_suggested', 'instagram_messages_tab']));
    expect(ids.toSet().length, ids.length);
  });
}
