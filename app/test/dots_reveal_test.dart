import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:unloop/ui/dots.dart';

void main() {
  test('every dot is dark at the start and steady at the end', () {
    for (var letter = 0; letter < 12; letter++) {
      for (var row = 0; row < 7; row++) {
        for (var col = 0; col < 5; col++) {
          expect(dotReveal(0, letter, 12, row, col), 0);
          expect(dotReveal(1, letter, 12, row, col), 1, reason: 'letter $letter row $row col $col must be lit at t = 1');
        }
      }
    }
  });

  test('letters light one after another, and each letter sweeps left to right', () {
    double at(int letter, int col) {
      // first time the dot starts to light
      for (var i = 0; i <= 1000; i++) {
        if (dotReveal(i / 1000, letter, 10, 3, col) > 0) return i / 1000;
      }
      return 2;
    }

    expect(at(0, 0) < at(1, 0), isTrue);
    expect(at(4, 0) < at(9, 0), isTrue);
    expect(at(3, 0) < at(3, 4), isTrue);
  });

  test('a lighting dot is between dark and steady', () {
    final mid = dotReveal(0.1, 1, 10, 0, 0);
    expect(mid > 0 && mid < 1, isTrue);
    double last = 0;
    for (var i = 0; i <= 100; i++) {
      final v = dotReveal(i / 100, 2, 10, 2, 2);
      expect(v >= last, isTrue, reason: 'light only ever comes on, never flickers back');
      last = v;
    }
  });

  testWidgets('DotText follows its reveal animation from dark to lit without errors', (t) async {
    final c = AnimationController(vsync: t, duration: const Duration(milliseconds: 500));
    await t.pumpWidget(Directionality(textDirection: TextDirection.ltr, child: DotText('HI', reveal: c)));
    for (final v in [0.0, 0.2, 0.5, 0.8, 1.0]) {
      c.value = v;
      await t.pump();
    }
    expect(c.value, 1);
    expect(find.byType(DotText), findsOneWidget);
    c.dispose();
  });

  testWidgets('the display panel fills the width and keeps the dot grid square', (t) async {
    await t.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: Align(alignment: Alignment.topLeft, child: SizedBox(width: 360, child: DotBoard(lines: const ['USE THE APPS', 'SKIP THE LOOP']))),
    ));
    final size = t.getSize(find.byType(DotBoard));
    expect(size.width, 360);
    // 13 letters widest -> 13*6-1+4 = 81 columns; 2 lines -> 2*8-1+4 = 19 rows
    expect((size.height - 2) / (size.width - 2) - 19 / 81 < 0.01, isTrue);
    expect(size.height > 60 && size.height < 140, isTrue);
  });
}
