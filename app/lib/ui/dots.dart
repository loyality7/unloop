import 'dart:math' as math;

import 'package:flutter/widgets.dart';

import 'tokens.dart';

/// 5x7 dot-matrix glyphs. Our own bitmap table.
const _g = <String, List<String>>{
  '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
  '1': ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
  '2': ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
  '3': ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
  '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
  '5': ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
  '6': ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
  '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
  '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
  '9': ['01110', '10001', '10001', '01111', '00001', '00010', '01100'],
  'A': ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
  'B': ['11110', '10001', '10001', '11110', '10001', '10001', '11110'],
  'C': ['01110', '10001', '10000', '10000', '10000', '10001', '01110'],
  'D': ['11100', '10010', '10001', '10001', '10001', '10010', '11100'],
  'E': ['11111', '10000', '10000', '11110', '10000', '10000', '11111'],
  'F': ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
  'G': ['01110', '10001', '10000', '10111', '10001', '10001', '01111'],
  'H': ['10001', '10001', '10001', '11111', '10001', '10001', '10001'],
  'I': ['01110', '00100', '00100', '00100', '00100', '00100', '01110'],
  'J': ['00111', '00010', '00010', '00010', '00010', '10010', '01100'],
  'K': ['10001', '10010', '10100', '11000', '10100', '10010', '10001'],
  'L': ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
  'M': ['10001', '11011', '10101', '10101', '10001', '10001', '10001'],
  'N': ['10001', '10001', '11001', '10101', '10011', '10001', '10001'],
  'O': ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
  'P': ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
  'Q': ['01110', '10001', '10001', '10001', '10101', '10010', '01101'],
  'R': ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
  'S': ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
  'T': ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
  'U': ['10001', '10001', '10001', '10001', '10001', '10001', '01110'],
  'V': ['10001', '10001', '10001', '10001', '10001', '01010', '00100'],
  'W': ['10001', '10001', '10001', '10101', '10101', '10101', '01010'],
  'X': ['10001', '10001', '01010', '00100', '01010', '10001', '10001'],
  'Y': ['10001', '10001', '01010', '00100', '00100', '00100', '00100'],
  'Z': ['11111', '00001', '00010', '00100', '01000', '10000', '11111'],
  ':': ['00000', '00100', '00100', '00000', '00100', '00100', '00000'],
  '.': ['00000', '00000', '00000', '00000', '00000', '00110', '00110'],
  '%': ['11001', '11010', '00010', '00100', '01000', '01011', '10011'],
  '-': ['00000', '00000', '00000', '11111', '00000', '00000', '00000'],
  '+': ['00000', '00100', '00100', '11111', '00100', '00100', '00000'],
  '/': ['00001', '00010', '00010', '00100', '01000', '01000', '10000'],
  '~': ['00000', '00000', '01000', '10101', '00010', '00000', '00000'],
};
const _blank = ['00000', '00000', '00000', '00000', '00000', '00000', '00000'];

/// How far a single dot has lit up, 0 (dark) to 1 (steady), when the whole text is revealed at time [t] (0 to 1).
/// Letters light one after another from the left. Inside a letter the light sweeps left to right with a slight
/// downward slant. Every dot is fully lit by t = 1.
double dotReveal(double t, int letter, int letters, int row, int col) {
  const settle = 0.14; // how long one dot takes from spark to steady
  final n = letters <= 1 ? 1 : letters;
  final start = (letter / n) * 0.58;
  final within = (col / 4) * 0.20 + (row / 6) * 0.06;
  return ((t - (start + within)) / settle).clamp(0.0, 1.0);
}

/// Dot-matrix text. Digits, A-Z and a few symbols; anything else is blank.
/// With [reveal] the text lights up letter by letter: each dot sparks in the accent colour with a soft halo and then
/// settles to [color], like a display switching on.
class DotText extends StatelessWidget {
  const DotText(this.text, {super.key, this.pitch = 5, this.color = C.fg, this.showOff = true, this.reveal});
  final String text;
  final double pitch;
  final Color color;
  final bool showOff; // draw unlit dots faintly, like a real display
  final Animation<double>? reveal;
  @override
  Widget build(BuildContext context) => CustomPaint(
        size: Size(math.max(1, text.length * 6 - 1) * pitch, 7 * pitch),
        painter: _DotTextPainter(text.toUpperCase(), pitch, color, showOff, reveal),
      );
}

class _DotTextPainter extends CustomPainter {
  _DotTextPainter(this.text, this.pitch, this.color, this.showOff, this.reveal) : super(repaint: reveal);
  final String text;
  final double pitch;
  final Color color;
  final bool showOff;
  final Animation<double>? reveal;
  @override
  void paint(Canvas canvas, Size size) {
    final on = Paint()..color = color;
    final off = Paint()..color = C.dim;
    final r = pitch * 0.34;
    final t = reveal?.value ?? 1.0;
    for (var i = 0; i < text.length; i++) {
      final glyph = _g[text[i]] ?? _blank;
      for (var row = 0; row < 7; row++) {
        for (var col = 0; col < 5; col++) {
          final lit = glyph[row][col] == '1';
          if (!lit && (!showOff || text[i] == ' ')) continue;
          final centre = Offset((i * 6 + col) * pitch + pitch / 2, row * pitch + pitch / 2);
          if (!lit) {
            canvas.drawCircle(centre, r, off);
            continue;
          }
          final p = reveal == null ? 1.0 : dotReveal(t, i, text.length, row, col);
          if (p >= 1) {
            canvas.drawCircle(centre, r, on);
          } else if (p <= 0) {
            canvas.drawCircle(centre, r, off); // not lit yet: stays a dark dot
          } else {
            final glow = 1 - p; // 1 at the spark, 0 once steady
            canvas.drawCircle(centre, r * (1 + 2.2 * glow), Paint()..color = C.signal.withValues(alpha: 0.30 * glow));
            canvas.drawCircle(centre, r * (1 + 0.45 * glow), Paint()..color = Color.lerp(C.signal, color, Curves.easeOut.transform(p))!);
          }
        }
      }
    }
  }

  @override
  bool shouldRepaint(_DotTextPainter o) => o.text != text || o.pitch != pitch || o.color != color || o.showOff != showOff || o.reveal != reveal;
}

/// A black display panel with square corners: the whole rectangle is a grid of dark dots and the lines of text light up
/// inside it, letter by letter, like an LED board switching on. When it finishes a red cursor blinks after the last line.
/// Sized to the available width. [reveal] runs 0 to 1; without it the text is simply lit.
class DotBoard extends StatefulWidget {
  const DotBoard({super.key, required this.lines, this.reveal, this.color = C.fg});
  final List<String> lines;
  final Animation<double>? reveal;
  final Color color;

  static const _margin = 2; // dark dots around the text, in grid cells

  static int _cols(List<String> lines) => lines.fold<int>(1, (m, l) => math.max(m, l.length)) * 6 - 1 + 2 * _margin;
  static int _rows(List<String> lines) => lines.length * 8 - 1 + 2 * _margin;

  @override
  State<DotBoard> createState() => _DotBoardState();
}

class _DotBoardState extends State<DotBoard> with SingleTickerProviderStateMixin {
  late final AnimationController _blink = AnimationController(vsync: this, duration: const Duration(milliseconds: 1100));

  @override
  void initState() {
    super.initState();
    _blink.repeat();
  }

  @override
  void dispose() {
    _blink.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (context, box) {
          final width = box.maxWidth.isFinite ? box.maxWidth : 320.0;
          final pitch = (width - 2) / DotBoard._cols(widget.lines); // 1 px border each side
          final height = DotBoard._rows(widget.lines) * pitch + 2;
          return Container(
            width: width,
            height: height,
            decoration: BoxDecoration(color: const Color(0xFF000000), border: Border.all(color: C.line)),
            child: ClipRect(
              child: CustomPaint(
                painter: _BoardPainter([for (final l in widget.lines) l.toUpperCase()], pitch, widget.color, widget.reveal, _blink),
              ),
            ),
          );
        },
      );
}

class _BoardPainter extends CustomPainter {
  _BoardPainter(this.lines, this.pitch, this.color, this.reveal, this.blink) : super(repaint: Listenable.merge([reveal, blink]));
  final List<String> lines;
  final double pitch;
  final Color color;
  final Animation<double>? reveal;
  final Animation<double> blink;

  @override
  void paint(Canvas canvas, Size size) {
    final off = Paint()..color = C.dim;
    final r = pitch * 0.34;
    final cols = DotBoard._cols(lines), rows = DotBoard._rows(lines);
    // 1. the whole panel is dark dots
    for (var y = 0; y < rows; y++) {
      for (var x = 0; x < cols; x++) {
        canvas.drawCircle(Offset(x * pitch + pitch / 2, y * pitch + pitch / 2), r, off);
      }
    }
    // 2. the text lights up inside it. Letters are numbered across all lines so the light flows line after line.
    final total = lines.fold<int>(0, (a, l) => a + l.length);
    final t = reveal?.value ?? 1.0;
    var letter = 0;
    for (var li = 0; li < lines.length; li++) {
      final line = lines[li];
      for (var i = 0; i < line.length; i++, letter++) {
        final glyph = _g[line[i]] ?? _blank;
        for (var row = 0; row < 7; row++) {
          for (var col = 0; col < 5; col++) {
            if (glyph[row][col] != '1') continue;
            final centre = Offset(
              (DotBoard._margin + i * 6 + col) * pitch + pitch / 2,
              (DotBoard._margin + li * 8 + row) * pitch + pitch / 2,
            );
            final p = reveal == null ? 1.0 : dotReveal(t, letter, total, row, col);
            if (p >= 1) {
              canvas.drawCircle(centre, r, Paint()..color = color);
            } else if (p > 0) {
              final glow = 1 - p;
              canvas.drawCircle(centre, r * (1 + 2.2 * glow), Paint()..color = C.signal.withValues(alpha: 0.30 * glow));
              canvas.drawCircle(centre, r * (1 + 0.45 * glow), Paint()..color = Color.lerp(C.signal, color, Curves.easeOut.transform(p))!);
            }
          }
        }
      }
    }
    // 3. once everything is lit, a red cursor blinks after the last line
    if (t >= 0.99 && blink.value < 0.55) {
      final lastLine = lines.length - 1;
      final cx = DotBoard._margin + lines.last.length * 6;
      for (var col = 0; col < 5; col++) {
        final centre = Offset((cx + col) * pitch + pitch / 2, (DotBoard._margin + lastLine * 8 + 6) * pitch + pitch / 2);
        canvas.drawCircle(centre, r * 1.1, Paint()..color = C.signal);
      }
    }
  }

  @override
  bool shouldRepaint(_BoardPainter o) => o.lines != lines || o.pitch != pitch || o.color != color || o.reveal != reveal;
}

/// An outline paint (written as a function because `..color = c..style = x` would cascade on `c`).
Paint strokePaint(Color c, double width) {
  final p = Paint();
  p.color = c;
  p.style = PaintingStyle.stroke;
  p.strokeWidth = width;
  return p;
}

/// A dotted line chart. Dots trace the line between the points, each point is a bigger dot, and the highlighted point
/// (the last one by default) gets a ring in the accent colour. Empty or single-value data draws a flat line.
class DotSparkline extends StatelessWidget {
  const DotSparkline({super.key, required this.values, this.height = 90, this.highlight});
  final List<double> values;
  final double height;
  final int? highlight;
  @override
  Widget build(BuildContext context) => SizedBox(
        height: height,
        width: double.infinity,
        child: CustomPaint(painter: _SparkPainter(values, highlight ?? (values.isEmpty ? 0 : values.length - 1))),
      );
}

class _SparkPainter extends CustomPainter {
  _SparkPainter(this.values, this.hi);
  final List<double> values;
  final int hi;

  @override
  void paint(Canvas canvas, Size s) {
    const pad = 10.0;
    final n = values.length;
    final maxV = values.fold<double>(1, math.max);
    Offset at(int i) {
      final x = n <= 1 ? s.width / 2 : pad + i * (s.width - 2 * pad) / (n - 1);
      final v = n == 0 ? 0.0 : values[i];
      return Offset(x, s.height - pad - (v / maxV) * (s.height - 2 * pad));
    }

    final trace = Paint()..color = C.text3;
    final point = Paint()..color = C.fg;
    if (n == 0) {
      for (double x = pad; x < s.width - pad; x += 6) {
        canvas.drawCircle(Offset(x, s.height - pad), 1.1, trace);
      }
      return;
    }
    for (var i = 0; i < n - 1; i++) {
      final a = at(i), b = at(i + 1);
      final steps = math.max(1, ((b - a).distance / 6).round());
      for (var k = 1; k < steps; k++) {
        canvas.drawCircle(Offset.lerp(a, b, k / steps)!, 1.1, trace);
      }
    }
    for (var i = 0; i < n; i++) {
      canvas.drawCircle(at(i), i == hi ? 3.2 : 2.2, i == hi ? (Paint()..color = C.signal) : point);
    }
    if (hi >= 0 && hi < n) {
      canvas.drawCircle(at(hi), 8, strokePaint(C.signal, 1.2));
    }
  }

  @override
  bool shouldRepaint(_SparkPainter o) => o.values != values || o.hi != hi;
}

/// Concentric dotted rings that spread outward and fade, like a calm sonar. Decoration for waiting moments.
class DotRipple extends StatefulWidget {
  const DotRipple({super.key, this.size = 240, this.rings = 4, this.color = C.fg});
  final double size;
  final int rings;
  final Color color;
  @override
  State<DotRipple> createState() => _DotRippleState();
}

class _DotRippleState extends State<DotRipple> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(seconds: 5));
  bool _started = false;

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_started) {
      _started = true;
      // With "remove animations" on, show one still frame instead of moving rings.
      if (MediaQuery.maybeOf(context)?.disableAnimations ?? false) {
        _c.value = 0.35;
      } else {
        _c.repeat();
      }
    }
    return RepaintBoundary(
      child: CustomPaint(size: Size.square(widget.size), painter: _RipplePainter(_c, widget.rings, widget.color)),
    );
  }
}

class _RipplePainter extends CustomPainter {
  _RipplePainter(this.c, this.rings, this.color) : super(repaint: c);
  final Animation<double> c;
  final int rings;
  final Color color;

  @override
  void paint(Canvas canvas, Size s) {
    final centre = s.center(Offset.zero);
    final maxR = s.width / 2 - 2;
    for (var k = 0; k < rings; k++) {
      final p = (c.value + k / rings) % 1.0;
      final rad = p * maxR;
      if (rad < 6) continue;
      final dots = (2 * math.pi * rad / 7).round().clamp(6, 140);
      final paint = Paint()..color = color.withValues(alpha: (1 - p) * 0.5);
      for (var i = 0; i < dots; i++) {
        final a = i * 2 * math.pi / dots;
        canvas.drawCircle(centre + Offset(math.cos(a), math.sin(a)) * rad, 1.3, paint);
      }
    }
  }

  @override
  bool shouldRepaint(_RipplePainter o) => o.rings != rings || o.color != color;
}

/// A dotted heartbeat line. While [on] a pulse travels along it in the accent colour; when off the line is flat and dim.
class DotPulse extends StatefulWidget {
  const DotPulse({super.key, this.on = true, this.color = C.fg, this.height = 26});
  final bool on;
  final Color color;
  final double height;
  @override
  State<DotPulse> createState() => _DotPulseState();
}

class _DotPulseState extends State<DotPulse> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 2600));

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final still = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (widget.on && !still) {
      if (!_c.isAnimating) _c.repeat();
    } else {
      _c.stop();
    }
    return SizedBox(
      height: widget.height,
      width: double.infinity,
      child: CustomPaint(painter: _PulsePainter(_c, widget.on, widget.color)),
    );
  }
}

class _PulsePainter extends CustomPainter {
  _PulsePainter(this.c, this.on, this.color) : super(repaint: c);
  final Animation<double> c;
  final bool on;
  final Color color;

  @override
  void paint(Canvas canvas, Size s) {
    final mid = s.height / 2;
    final tip = (c.value * (s.width + 80)) - 40; // where the beat is right now
    final base = Paint()..color = on ? color.withValues(alpha: 0.45) : C.dim;
    for (double x = 2; x < s.width; x += 6) {
      var y = mid;
      var accent = false;
      if (on) {
        final dx = x - tip;
        final beat = math.exp(-math.pow(dx / 11, 2)) * math.cos(dx / 3.2) * s.height * 0.46;
        y = mid - beat;
        accent = dx.abs() < 14;
      }
      canvas.drawCircle(Offset(x, y), accent ? 1.9 : 1.2, accent ? (Paint()..color = C.signal) : base);
    }
  }

  @override
  bool shouldRepaint(_PulsePainter o) => o.on != on || o.color != color;
}

/// Ring made of dots. Lit dots show progress; the leading dot can glow red.
class DotRing extends StatelessWidget {
  const DotRing({super.key, required this.progress, this.size = 120, this.dots = 48, this.tip = true, this.child});
  final double progress;
  final double size;
  final int dots;
  final bool tip;
  final Widget? child;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: size,
        height: size,
        child: Stack(alignment: Alignment.center, children: [
          TweenAnimationBuilder<double>(
            tween: Tween(end: progress.clamp(0, 1).toDouble()),
            duration: const Duration(milliseconds: 800),
            curve: Curves.easeOutCubic,
            builder: (_, v, _) => CustomPaint(size: Size.square(size), painter: _RingPainter(v, dots, tip)),
          ),
          if (child != null) child!,
        ]),
      );
}

class _RingPainter extends CustomPainter {
  _RingPainter(this.v, this.n, this.tip);
  final double v;
  final int n;
  final bool tip;
  final Color sig = C.signal;
  @override
  void paint(Canvas c, Size s) {
    final dotR = math.max(1.2, s.width / n * 0.55);
    final rad = s.width / 2 - dotR - 1;
    final lit = (v * n).round();
    for (var i = 0; i < n; i++) {
      final a = -math.pi / 2 + i * 2 * math.pi / n;
      final isTip = tip && lit > 0 && i == lit - 1 && lit < n;
      final color = isTip ? C.signal : (i < lit ? C.fg : C.dim);
      c.drawCircle(Offset(s.width / 2 + rad * math.cos(a), s.height / 2 + rad * math.sin(a)), isTip ? dotR * 1.3 : dotR, Paint()..color = color);
    }
  }

  @override
  bool shouldRepaint(_RingPainter o) => o.v != v || o.n != n || o.tip != tip || o.sig != sig;
}

/// Bars drawn as stacks of dots. The highlighted bar is white (and red on top if [alert]).
class DotBars extends StatelessWidget {
  const DotBars({super.key, required this.values, required this.labels, this.highlight, this.rows = 10, this.pitch = 8, this.alert = false});
  final List<double> values;
  final List<String> labels;
  final int? highlight;
  final int rows;
  final double pitch;
  final bool alert;
  @override
  Widget build(BuildContext context) {
    final maxV = values.fold<double>(1, math.max);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        for (var i = 0; i < values.length; i++)
          Expanded(
            child: Column(children: [
              Center(
                child: CustomPaint(
                  size: Size(pitch * 2, rows * pitch),
                  painter: _BarPainter((values[i] / maxV * rows).round().clamp(1, rows), rows, pitch, i == highlight, alert && i == highlight),
                ),
              ),
              const SizedBox(height: S.x2),
              Text(labels[i], style: T.label.copyWith(fontSize: 10, letterSpacing: 0.8, color: i == highlight ? C.fg : C.text3)),
            ]),
          ),
      ],
    );
  }
}

class _BarPainter extends CustomPainter {
  _BarPainter(this.lit, this.rows, this.pitch, this.hot, this.alert);
  final int lit, rows;
  final double pitch;
  final bool hot, alert;
  final Color sig = C.signal;
  @override
  void paint(Canvas c, Size s) {
    final r = pitch * 0.34;
    for (var row = 0; row < rows; row++) {
      final fromBottom = rows - 1 - row;
      final on = fromBottom < lit;
      final top = fromBottom == lit - 1;
      final color = !on ? C.dim : (alert && top ? C.signal : (hot ? C.fg : C.text3));
      for (var col = 0; col < 2; col++) {
        c.drawCircle(Offset(col * pitch + pitch / 2, row * pitch + pitch / 2), r, Paint()..color = color);
      }
    }
  }

  @override
  bool shouldRepaint(_BarPainter o) => o.lit != lit || o.hot != hot || o.alert != alert || o.sig != sig;
}

/// Horizontal meter made of dots. Over [warnAt] the lit dots turn red.
class DotMeter extends StatelessWidget {
  const DotMeter({super.key, required this.value, this.dots = 30, this.warnAt = 0.9});
  final double value;
  final int dots;
  final double warnAt;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (_, box) => CustomPaint(
          size: Size(box.maxWidth, 8),
          painter: _MeterPainter(value.clamp(0, 1).toDouble(), dots, value >= warnAt),
        ),
      );
}

class _MeterPainter extends CustomPainter {
  _MeterPainter(this.v, this.n, this.warn);
  final double v;
  final int n;
  final bool warn;
  final Color sig = C.signal;
  @override
  void paint(Canvas c, Size s) {
    final step = s.width / n;
    final lit = (v * n).round();
    for (var i = 0; i < n; i++) {
      c.drawCircle(Offset(i * step + step / 2, s.height / 2), step * 0.26, Paint()..color = i < lit ? (warn ? C.signal : C.fg) : C.dim);
    }
  }

  @override
  bool shouldRepaint(_MeterPainter o) => o.v != v || o.warn != warn || o.sig != sig;
}
