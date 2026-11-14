import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:unloop/screens/controls.dart';
import 'package:unloop/ui/friction.dart';
import 'package:unloop/ui/kit.dart';

Widget _harness(Widget home) => WidgetsApp(
      color: const Color(0xFF000000),
      pageRouteBuilder: <T>(settings, builder) => PageRouteBuilder<T>(settings: settings, pageBuilder: (c, _, _) => builder(c)),
      home: home,
    );

void main() {
  testWidgets('friction makes the user wait 60 seconds, then lets them through', (t) async {
    t.view.physicalSize = const Size(900, 2600);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    late BuildContext ctx;
    await t.pumpWidget(_harness(Builder(builder: (c) {
      ctx = c;
      return const SizedBox();
    })));

    bool? result;
    askFriction(ctx, title: 'Test', key: 'k-wait').then((v) => result = v);
    await t.pump();
    await t.pump(const Duration(milliseconds: 300));
    expect(find.text('Wait 60s'), findsOneWidget);

    await t.pump(const Duration(seconds: 30));
    expect(find.text('Wait 30s'), findsOneWidget);
    await t.tap(find.text('Wait 30s'));
    await t.pump();
    expect(result, isNull, reason: 'the button does nothing before the wait is over');

    await t.pump(const Duration(seconds: 31));
    expect(find.text('Turn it off anyway'), findsOneWidget);
    await t.tap(find.text('Turn it off anyway'));
    await t.pump(const Duration(milliseconds: 400)); // the calm ripple never stops, so do not wait for it to settle
    expect(result, isTrue);

    // Passing once unlocks the same kind of change for a few minutes.
    expect(await askFriction(ctx, title: 'Test', key: 'k-wait'), isTrue);
  });

  testWidgets('backing out of the wait keeps the protection', (t) async {
    t.view.physicalSize = const Size(900, 2600);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    late BuildContext ctx;
    await t.pumpWidget(_harness(Builder(builder: (c) {
      ctx = c;
      return const SizedBox();
    })));
    bool? result;
    askFriction(ctx, title: 'Test', key: 'k-back').then((v) => result = v);
    await t.pump();
    await t.pump(const Duration(milliseconds: 300));
    await t.tap(find.text('Keep my protection'));
    await t.pump(const Duration(milliseconds: 400));
    expect(result, isFalse);
  });

  testWidgets('a row with a switch toggles when tapped anywhere on it', (t) async {
    var on = false;
    await t.pumpWidget(_harness(StatefulBuilder(
      builder: (c, set) => Row2(title: 'Reels', trailing: Toggle(value: on, onChanged: (v) => set(() => on = v))),
    )));
    await t.tap(find.text('Reels'));
    await t.pump();
    expect(on, isTrue);
  });

  testWidgets('press feedback appears at once, even inside a scroll view', (t) async {
    await t.pumpWidget(_harness(SingleChildScrollView(child: Press(onTap: () {}, child: const SizedBox(width: 200, height: 80)))));
    final g = await t.startGesture(t.getCenter(find.byType(Press)));
    await t.pump(const Duration(milliseconds: 70));
    final opacity = t.widget<AnimatedOpacity>(find.byType(AnimatedOpacity)).opacity;
    expect(opacity, lessThan(1), reason: 'pressed state must show before the 100 ms tap delay');
    await g.up();
  });

  testWidgets('a row with nothing to tap is fully bright, not dimmed like a disabled control', (t) async {
    await t.pumpWidget(_harness(const Row2(title: 'Kept', sub: 'Messages, stories')));
    expect(find.byType(AnimatedOpacity), findsNothing, reason: 'info rows must not go through the disabled-looking press wrapper');
    await t.pumpWidget(_harness(Row2(title: 'Open', onTap: () {})));
    expect(t.widget<AnimatedOpacity>(find.byType(AnimatedOpacity)).opacity, 1);
    await t.pumpWidget(_harness(const Btn('Off')));
    expect(t.widget<AnimatedOpacity>(find.byType(AnimatedOpacity)).opacity, lessThan(1), reason: 'a button with no action still looks disabled');
  });

  testWidgets('a notice with an action is tappable all over, not only on its label', (t) async {
    var taps = 0;
    await t.pumpWidget(_harness(Align(alignment: Alignment.topLeft, child: Notice('Blocking is not running.', warn: true, action: 'Fix', onAction: () => taps++))));
    await t.tap(find.text('Blocking is not running.'));
    await t.tap(find.text('FIX'));
    expect(taps, 2);
  });

  testWidgets('the consent screen says what to do when Accessibility is already on', (t) async {
    t.view.physicalSize = const Size(900, 2600);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    await t.pumpWidget(_harness(const DisclosureScreen(alreadyOn: true)));
    expect(find.textContaining('service is on'), findsOneWidget); // the title is drawn in dots, so check the sentence under it
    expect(find.text('I understand and agree'), findsOneWidget);
    expect(find.text('Turn Unloop off'), findsOneWidget);
    await t.pumpWidget(_harness(const DisclosureScreen()));
    expect(find.textContaining('uses Android'), findsOneWidget);
    expect(find.text('Not now'), findsOneWidget);
  });
}
