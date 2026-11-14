import 'package:flutter_test/flutter_test.dart';
import 'package:unloop/main.dart';
import 'package:unloop/native_bridge.dart';
import 'package:unloop/state.dart';
import 'package:unloop/ui/dots.dart';
import 'package:unloop/ui/tokens.dart';

void main() {
  test('blocking is active only when enabled AND connected', () {
    expect(PermissionState.unknown.blockingActive, isFalse);
    const enabledOnly = PermissionState(accessibilityEnabled: true, accessibilityConnected: false, usageAccess: false);
    expect(enabledOnly.blockingActive, isFalse);
    const both = PermissionState(accessibilityEnabled: true, accessibilityConnected: true, usageAccess: false);
    expect(both.blockingActive, isTrue);
  });

  test('C.res maps the red sentinel to the chosen accent only', () {
    C.signal = C.palette[5].$2;
    expect(C.res(C.red), C.palette[5].$2);
    expect(C.res(C.fg), C.fg);
    C.signal = C.red;
  });

  testWidgets('first launch shows onboarding', (t) async {
    await t.pumpWidget(const UnloopApp());
    await t.runAsync(app.load); // real async: platform-channel misses resolve off the fake clock
    await t.pump(const Duration(milliseconds: 800));
    expect(app.loaded, isTrue);
    expect(find.byType(DotBoard), findsWidgets);
  });
}
