import 'package:flutter/material.dart' show Icons; // glyphs only; no Material widgets are used
import 'package:flutter/services.dart' show TextInputAction;
import 'package:flutter/widgets.dart';

import 'dots.dart';
import 'tokens.dart';

export 'package:flutter/material.dart' show Icons;
export 'dots.dart';

// ---------- navigation ----------

Future<V?> go<V>(BuildContext c, Widget page) => Navigator.of(c).push<V>(PageRouteBuilder<V>(
      transitionDuration: const Duration(milliseconds: 170),
      reverseTransitionDuration: const Duration(milliseconds: 130),
      pageBuilder: (_, _, _) => page,
      transitionsBuilder: (_, a, _, child) {
        final curve = CurvedAnimation(parent: a, curve: Curves.easeOutCubic);
        return FadeTransition(
          opacity: curve,
          child: SlideTransition(position: Tween(begin: const Offset(0, 0.015), end: Offset.zero).animate(curve), child: child),
        );
      },
    ));

// ---------- press feedback ----------

/// Instant press feedback. It listens to raw pointer events: a GestureDetector inside a scroll view waits ~100 ms
/// to rule out a drag before it reports "down", which made every card feel dead. [flash] also tints the background,
/// for wide rows where a tiny scale change is invisible. Feedback stops as soon as the finger starts to drag.
class Press extends StatefulWidget {
  const Press({super.key, required this.child, this.onTap, this.scale = 0.96, this.flash = false});
  final Widget child;
  final VoidCallback? onTap;
  final double scale;
  final bool flash;
  @override
  State<Press> createState() => _PressState();
}

class _PressState extends State<Press> {
  bool _down = false;
  Offset? _start;

  void _set(bool v) {
    if (_down != v && mounted) setState(() => _down = v);
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onTap != null;
    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: enabled ? (e) { _start = e.position; _set(true); } : null,
      onPointerMove: (e) {
        final s = _start;
        if (_down && s != null && (e.position - s).distance > 10) _set(false);
      },
      onPointerUp: (_) => _set(false),
      onPointerCancel: (_) => _set(false),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: ColoredBox(
          color: widget.flash && _down ? C.raised : const Color(0x00000000),
          child: AnimatedScale(
            scale: _down ? widget.scale : 1,
            duration: const Duration(milliseconds: 60),
            curve: Curves.easeOut,
            child: AnimatedOpacity(
              opacity: !enabled ? 0.4 : (_down ? 0.7 : 1),
              duration: const Duration(milliseconds: 60),
              child: widget.child,
            ),
          ),
        ),
      ),
    );
  }
}

// ---------- page frame ----------

class Screen extends StatelessWidget {
  const Screen({super.key, this.title, this.subtitle, required this.children, this.back = true, this.trailing, this.bottom});
  final String? title, subtitle;
  final List<Widget> children;
  final bool back;
  final Widget? trailing;
  final Widget? bottom;

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
        color: C.bg,
        // The four tab screens sit above the bottom dock, which already clears the gesture bar: reserving it here as well
        // left a big empty band above the dock. Screens with a back button have no dock and keep the bottom inset.
        child: SafeArea(
          bottom: back,
          child: Column(children: [
            if (back || trailing != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(S.gutter, S.x3, S.gutter, 0),
                child: Row(children: [
                  if (back) RoundBtn(Icons.arrow_back_rounded, size: 44, onTap: () => Navigator.of(context).maybePop()),
                  const Spacer(),
                  ?trailing,
                ]),
              ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(S.gutter, S.x5, S.gutter, S.x8),
                physics: const BouncingScrollPhysics(),
                children: [
                  if (title != null)
                    Align(
                      alignment: Alignment.centerLeft,
                      child: FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: DotText(title!, pitch: 5)),
                    ),
                  if (subtitle != null) ...[const SizedBox(height: S.x4), Text(subtitle!, style: T.bodyMuted)],
                  if (title != null) const SizedBox(height: S.x6),
                  ...children,
                ],
              ),
            ),
            if (bottom != null) Padding(padding: const EdgeInsets.fromLTRB(S.gutter, S.x2, S.gutter, S.x4), child: bottom),
          ]),
        ),
      );
  }
}

// ---------- labels ----------

class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key, this.index});
  final String text;
  final String? index;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(S.x2, S.x8, 0, S.x3),
        child: Text('${index != null ? '$index  ' : ''}${text.toUpperCase()}', style: T.label),
      );
}

// ---------- round buttons (icon) ----------

/// Circular icon button. [on] = filled white with ink icon; off = dark with white icon.
class RoundBtn extends StatelessWidget {
  const RoundBtn(this.icon, {super.key, this.on = false, this.size = 56, this.iconSize, this.onTap});
  final IconData icon;
  final bool on;
  final double size;

  /// Glyph size inside the circle. Defaults to 40% of the circle.
  final double? iconSize;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Press(
        onTap: onTap,
        scale: 0.92,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          width: size,
          height: size,
          decoration: BoxDecoration(color: on ? C.fg : C.raised, shape: BoxShape.circle),
          child: Icon(icon, size: iconSize ?? size * 0.4, color: on ? C.ink : C.fg),
        ),
      );
}

// ---------- buttons ----------

enum BtnKind { primary, secondary, ghost, danger }

class Btn extends StatelessWidget {
  const Btn(this.label, {super.key, this.onTap, this.kind = BtnKind.primary, this.icon, this.expand = true, this.small = false});
  final String label;
  final VoidCallback? onTap;
  final BtnKind kind;
  final IconData? icon;
  final bool expand, small;

  @override
  Widget build(BuildContext context) {
    final (bg, fg, border) = switch (kind) {
      BtnKind.primary => (C.fg, C.ink, C.fg),
      BtnKind.secondary => (C.raised, C.fg, C.line),
      BtnKind.ghost => (const Color(0x00000000), C.text2, const Color(0x00000000)),
      BtnKind.danger => (const Color(0x00000000), C.signal, C.signal),
    };
    return Press(
      onTap: onTap,
      child: Container(
        height: small ? 40 : 56,
        padding: EdgeInsets.symmetric(horizontal: small ? S.x5 : S.x6),
        decoration: BoxDecoration(color: bg, borderRadius: R.pill, border: Border.all(color: border)),
        alignment: Alignment.center,
        child: Row(
          mainAxisSize: expand ? MainAxisSize.max : MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (icon != null) ...[Icon(icon, size: 18, color: fg), const SizedBox(width: S.x2)],
            Text(label, style: T.button.copyWith(color: fg, fontSize: small ? 14 : 15)),
          ],
        ),
      ),
    );
  }
}

// ---------- toggle ----------

class Toggle extends StatelessWidget {
  const Toggle({super.key, required this.value, this.onChanged});
  final bool value;
  final ValueChanged<bool>? onChanged;
  @override
  Widget build(BuildContext context) => Press(
        scale: 0.94,
        onTap: onChanged == null ? null : () => onChanged!(!value),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 110),
          curve: Curves.easeOutCubic,
          width: 54,
          height: 32,
          padding: const EdgeInsets.all(4),
          decoration: BoxDecoration(color: value ? C.fg : C.raised, borderRadius: R.pill),
          child: AnimatedAlign(
            duration: const Duration(milliseconds: 110),
            curve: Curves.easeOutCubic,
            alignment: value ? Alignment.centerRight : Alignment.centerLeft,
            child: Container(width: 24, height: 24, decoration: BoxDecoration(color: value ? C.ink : C.text3, shape: BoxShape.circle)),
          ),
        ),
      );
}

// ---------- tiles and surfaces ----------

/// The signature card: rounded square, mono caps label, optional index and trailing widget.
class Tile extends StatelessWidget {
  const Tile({super.key, this.label, this.index, this.trailing, required this.child, this.light = false, this.onTap, this.pad = const EdgeInsets.all(S.x5)});
  final String? label, index;
  final Widget? trailing;
  final Widget child;
  final bool light;
  final VoidCallback? onTap;
  final EdgeInsets pad;
  @override
  Widget build(BuildContext context) {
    final box = Container(
      width: double.infinity,
      padding: pad,
      decoration: BoxDecoration(color: light ? C.light : C.surface, borderRadius: R.of(R.md)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
        if (label != null)
          Padding(
            padding: const EdgeInsets.only(bottom: S.x4),
            child: Row(children: [
              if (index != null) Text('$index  ', style: T.label.copyWith(color: light ? C.ink : C.text)),
              Expanded(child: Text(label!.toUpperCase(), style: T.label.copyWith(color: light ? C.ink.withValues(alpha: 0.6) : C.text2))),
              if (trailing != null) trailing! else if (onTap != null) Icon(Icons.arrow_forward_rounded, size: 16, color: light ? C.ink.withValues(alpha: 0.5) : C.text3),
            ]),
          ),
        child,
      ]),
    );
    return onTap == null ? box : Press(onTap: onTap, scale: 0.97, child: box);
  }
}

/// Alias for a plain tile without a header.
class Panel extends StatelessWidget {
  const Panel({super.key, required this.child, this.onTap, this.light = false, this.pad = const EdgeInsets.all(S.x5)});
  final Widget child;
  final VoidCallback? onTap;
  final bool light;
  final EdgeInsets pad;
  @override
  Widget build(BuildContext context) => Tile(onTap: onTap, light: light, pad: pad, child: child);
}

/// Rows in one tile, separated by a dotted hairline.
class Group extends StatelessWidget {
  const Group(this.rows, {super.key});
  final List<Widget> rows;
  @override
  Widget build(BuildContext context) => Container(
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(color: C.surface, borderRadius: R.of(R.md)),
        child: Column(children: [
          for (var i = 0; i < rows.length; i++) ...[
            if (i > 0) const _DottedLine(),
            rows[i],
          ],
        ]),
      );
}

class _DottedLine extends StatelessWidget {
  const _DottedLine();
  @override
  Widget build(BuildContext context) => const Padding(
        padding: EdgeInsets.symmetric(horizontal: S.x5),
        child: SizedBox(height: 2, width: double.infinity, child: CustomPaint(painter: _DotLinePainter())),
      );
}

class _DotLinePainter extends CustomPainter {
  const _DotLinePainter();
  @override
  void paint(Canvas c, Size s) {
    final p = Paint()..color = C.line;
    for (double x = 0; x < s.width; x += 6) {
      c.drawCircle(Offset(x + 1, 1), 0.8, p);
    }
  }

  @override
  bool shouldRepaint(_DotLinePainter o) => false;
}

class Row2 extends StatelessWidget {
  const Row2({super.key, required this.title, this.sub, this.icon, this.leading, this.iconTone = C.fg, this.trailing, this.onTap, this.chevron = false});
  final String title;
  final String? sub;
  final IconData? icon;

  /// Replaces [icon] with any widget, for example an app icon.
  final Widget? leading;
  final Color iconTone;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool chevron;

  /// A row whose trailing widget is a Toggle toggles when tapped anywhere, not only on the small switch.
  VoidCallback? get _tap {
    if (onTap != null) return onTap;
    final t = trailing;
    if (t is Toggle && t.onChanged != null) return () => t.onChanged!(!t.value);
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final body = Padding(
          padding: const EdgeInsets.symmetric(horizontal: S.x5, vertical: S.x4),
          child: Row(children: [
            if (leading != null) ...[leading!, const SizedBox(width: S.x4)] else if (icon != null) ...[IconBadge(icon!, tone: iconTone), const SizedBox(width: S.x4)],
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(title, style: T.body),
                if (sub != null) Padding(padding: const EdgeInsets.only(top: 2), child: Text(sub!, style: T.small)),
              ]),
            ),
            if (trailing != null) ...[const SizedBox(width: S.x3), trailing!],
            if (chevron) const Padding(padding: EdgeInsets.only(left: S.x2), child: Icon(Icons.arrow_forward_rounded, size: 18, color: C.text3)),
          ]),
    );
    // A row with nothing to tap stays fully bright. Only a real control gets press feedback (and the dimmed look
    // a disabled control has).
    return _tap == null ? body : Press(onTap: _tap, scale: 1, flash: true, child: body);
  }
}

class IconBadge extends StatelessWidget {
  const IconBadge(this.icon, {super.key, this.tone = C.fg, this.size = 40});
  final IconData icon;
  final Color tone;
  final double size;
  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: const BoxDecoration(color: C.raised, shape: BoxShape.circle),
        child: Icon(icon, size: size * 0.45, color: C.res(tone)),
      );
}

/// Outlined mono pill. [tone] red for alerts, white for active.
class Tag extends StatelessWidget {
  const Tag(this.text, {super.key, this.tone = C.text2, this.filled = false});
  final String text;
  final Color tone;
  final bool filled;
  @override
  Widget build(BuildContext context) {
    final t = C.res(tone);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 5),
      decoration: BoxDecoration(
        color: filled ? t : const Color(0x00000000),
        borderRadius: R.pill,
        border: Border.all(color: filled ? t : t.withValues(alpha: 0.5)),
      ),
      child: Text(text.toUpperCase(), style: T.label.copyWith(fontSize: 10, letterSpacing: 1.2, color: filled ? C.ink : t)),
    );
  }
}

/// Status dot. Red = live / needs attention. White = ok.
class Dot extends StatelessWidget {
  const Dot(this.color, {super.key, this.size = 8});
  final Color color;
  final double size;
  @override
  Widget build(BuildContext context) => Container(width: size, height: size, decoration: BoxDecoration(color: C.res(color), shape: BoxShape.circle));
}

/// Inline notice: red dot + mono text. Red tone for warnings.
class Notice extends StatelessWidget {
  const Notice(this.text, {super.key, this.warn = false, this.action, this.onAction});
  final String text;
  final bool warn;
  final String? action;
  final VoidCallback? onAction;
  @override
  Widget build(BuildContext context) {
    final box = Container(
      padding: const EdgeInsets.all(S.x4),
      decoration: BoxDecoration(color: C.surface, borderRadius: R.of(R.sm), border: Border.all(color: warn ? C.signal.withValues(alpha: 0.5) : C.line)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
        Padding(padding: const EdgeInsets.only(top: 1), child: Dot(warn ? C.signal : C.text3)),
        const SizedBox(width: S.x3),
        Expanded(child: Text(text, style: T.small.copyWith(color: C.text))),
        if (action != null) ...[
          const SizedBox(width: S.x3),
          // A real button, not a few pixels of text: easy to hit with a thumb.
          Container(
            padding: const EdgeInsets.symmetric(horizontal: S.x4, vertical: S.x3),
            decoration: BoxDecoration(borderRadius: R.pill, border: Border.all(color: warn ? C.signal : C.fg)),
            child: Text(action!.toUpperCase(), style: T.label.copyWith(color: warn ? C.signal : C.fg)),
          ),
        ],
      ]),
    );
    // With an action, the whole notice is the tap target.
    return onAction == null ? box : Press(onTap: onAction, scale: 0.99, child: box);
  }
}

// ---------- segmented ----------

class Segmented extends StatelessWidget {
  const Segmented({super.key, required this.options, required this.index, required this.onChanged});
  final List<String> options;
  final int index;
  final ValueChanged<int> onChanged;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(4),
        decoration: BoxDecoration(color: C.surface, borderRadius: R.pill),
        child: Row(children: [
          for (var i = 0; i < options.length; i++)
            Expanded(
              child: Press(
                onTap: () => onChanged(i),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  height: 38,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: i == index ? C.fg : const Color(0x00000000), borderRadius: R.pill),
                  child: Text(options[i].toUpperCase(), style: T.label.copyWith(color: i == index ? C.ink : C.text2, letterSpacing: 1.2)),
                ),
              ),
            ),
        ]),
      );
}

// ---------- day picker ----------

class DayPicker extends StatelessWidget {
  const DayPicker({super.key, required this.days, required this.onChanged});
  final List<bool> days; // Mon..Sun
  final ValueChanged<List<bool>> onChanged;
  static const _l = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
  @override
  Widget build(BuildContext context) => Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          for (var i = 0; i < 7; i++)
            Press(
              scale: 0.9,
              onTap: () => onChanged([...days]..[i] = !days[i]),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                width: 42,
                height: 42,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: days[i] ? C.fg : C.raised, shape: BoxShape.circle),
                child: Text(_l[i], style: T.label.copyWith(fontSize: 13, color: days[i] ? C.ink : C.text2)),
              ),
            ),
        ],
      );
}

// ---------- stepper ----------

class Stepper2 extends StatelessWidget {
  const Stepper2({super.key, required this.value, required this.onChanged, this.step = 15, this.min = 5, this.max = 480, this.format});
  final int value, step, min, max;
  final ValueChanged<int> onChanged;
  final String Function(int)? format;
  @override
  Widget build(BuildContext context) => Row(mainAxisSize: MainAxisSize.min, children: [
        RoundBtn(Icons.remove_rounded, size: 38, onTap: value > min ? () => onChanged(value - step) : null),
        SizedBox(width: 92, child: Text(format?.call(value) ?? '$value', textAlign: TextAlign.center, style: T.value.copyWith(fontSize: 18))),
        RoundBtn(Icons.add_rounded, size: 38, onTap: value < max ? () => onChanged(value + step) : null),
      ]);
}

// ---------- text input ----------

class Field extends StatefulWidget {
  const Field({super.key, required this.hint, this.onSubmit, this.onChanged, this.icon = Icons.search_rounded});
  final String hint;
  final ValueChanged<String>? onSubmit;
  final ValueChanged<String>? onChanged;
  final IconData icon;
  @override
  State<Field> createState() => _FieldState();
}

class _FieldState extends State<Field> {
  final _c = TextEditingController();
  final _f = FocusNode();
  @override
  void dispose() {
    _c.dispose();
    _f.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: _f.requestFocus,
        child: Container(
          height: 56,
          padding: const EdgeInsets.symmetric(horizontal: S.x5),
          decoration: BoxDecoration(color: C.surface, borderRadius: R.pill),
          child: Row(children: [
            Icon(widget.icon, size: 18, color: C.text3),
            const SizedBox(width: S.x3),
            Expanded(
              child: Stack(alignment: Alignment.centerLeft, children: [
                ListenableBuilder(
                  listenable: _c,
                  builder: (_, _) => _c.text.isEmpty ? Text(widget.hint.toUpperCase(), style: T.label.copyWith(color: C.text3)) : const SizedBox.shrink(),
                ),
                EditableText(
                  controller: _c,
                  focusNode: _f,
                  style: T.mono.copyWith(fontSize: 14),
                  cursorColor: C.signal,
                  backgroundCursorColor: C.line,
                  textInputAction: TextInputAction.done,
                  onChanged: widget.onChanged,
                  onSubmitted: (v) {
                    if (v.trim().isEmpty) return;
                    widget.onSubmit?.call(v.trim());
                    _c.clear();
                  },
                ),
              ]),
            ),
          ]),
        ),
      );
}

// ---------- brand mark ----------

/// The open loop: a dot ring that does not close.
class LoopMark extends StatelessWidget {
  const LoopMark({super.key, this.size = 36});
  final double size;
  @override
  Widget build(BuildContext context) => DotRing(progress: 0.86, size: size, dots: 20);
}

// ---------- stat helpers ----------

/// Big dot-matrix number with a mono caps caption.
class Stat extends StatelessWidget {
  const Stat(this.value, this.caption, {super.key, this.pitch = 5, this.color = C.fg});
  final String value, caption;
  final double pitch;
  final Color color;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        FittedBox(fit: BoxFit.scaleDown, child: DotText(value, pitch: pitch, color: color)),
        const SizedBox(height: S.x2),
        Text(caption.toUpperCase(), style: T.label),
      ]);
}

/// Small mono value with caption, for metric rows inside tiles.
class Metric extends StatelessWidget {
  const Metric(this.value, this.caption, {super.key, this.light = false});
  final String value, caption;
  final bool light;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(caption.toUpperCase(), style: T.label.copyWith(fontSize: 10, color: light ? C.ink.withValues(alpha: 0.6) : C.text3)),
        const SizedBox(height: 4),
        Text(value, style: T.value.copyWith(color: light ? C.ink : C.fg)),
      ]);
}

// ---------- dock (bottom nav) ----------

class NavItem {
  const NavItem(this.icon, this.label);
  final IconData icon;
  final String label;
}

/// Compact pill dock with round buttons packed close together. Active = white circle.
class BottomNav extends StatelessWidget {
  const BottomNav({super.key, required this.items, required this.index, required this.onChanged});
  final List<NavItem> items;
  final int index;
  final ValueChanged<int> onChanged;
  @override
  Widget build(BuildContext context) {
    // Clear of the phone's gesture bar completely: nothing is reserved beyond it, and the circles never touch it.
    final inset = MediaQuery.of(context).viewPadding.bottom;
    return ColoredBox(
      color: C.bg,
      child: Padding(
        padding: EdgeInsets.only(top: S.x1, bottom: inset + S.x1),
        child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          Container(
            padding: const EdgeInsets.all(S.x2),
            decoration: BoxDecoration(color: C.surface, borderRadius: R.pill),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              for (var i = 0; i < items.length; i++) ...[
                if (i > 0) const SizedBox(width: S.x6),
                RoundBtn(items[i].icon, on: i == index, size: 56, iconSize: 32, onTap: () => onChanged(i)),
              ],
            ]),
          ),
        ]),
      ),
    );
  }
}

// ---------- accent picker ----------

/// Row of round swatches. Selected swatch gets a white ring.
class ColorPick extends StatelessWidget {
  const ColorPick({super.key, required this.index, required this.onChanged});
  final int index;
  final ValueChanged<int> onChanged;
  @override
  Widget build(BuildContext context) => Wrap(spacing: S.x3, runSpacing: S.x3, children: [
        for (var i = 0; i < C.palette.length; i++)
          Press(
            scale: 0.88,
            onTap: () => onChanged(i),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 160),
              width: 44,
              height: 44,
              padding: const EdgeInsets.all(4),
              decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: i == index ? C.fg : const Color(0x00000000), width: 2)),
              child: Container(decoration: BoxDecoration(color: C.palette[i].$2, shape: BoxShape.circle)),
            ),
          ),
      ]);
}

// ---------- exact time picker ----------

/// Two wheels, hour (00-23) and minute (00-59), for an exact clock time. [minutes] is minutes since midnight.
class TimeWheel extends StatefulWidget {
  const TimeWheel({super.key, required this.minutes, required this.onChanged});
  final int minutes;
  final ValueChanged<int> onChanged;
  @override
  State<TimeWheel> createState() => _TimeWheelState();
}

class _TimeWheelState extends State<TimeWheel> {
  late final FixedExtentScrollController _h = FixedExtentScrollController(initialItem: widget.minutes ~/ 60);
  late final FixedExtentScrollController _m = FixedExtentScrollController(initialItem: widget.minutes % 60);
  late int _hour = widget.minutes ~/ 60, _min = widget.minutes % 60;

  @override
  void dispose() {
    _h.dispose();
    _m.dispose();
    super.dispose();
  }

  Widget _wheel(FixedExtentScrollController c, int count, ValueChanged<int> onPick, String caption) => Expanded(
        child: Column(children: [
          Text(caption, style: T.label.copyWith(fontSize: 10)),
          const SizedBox(height: 4),
          SizedBox(
            height: 132,
            child: Stack(alignment: Alignment.center, children: [
              IgnorePointer(
                child: Container(
                  height: 44,
                  decoration: BoxDecoration(
                    border: Border.symmetric(horizontal: BorderSide(color: C.signal, width: 1)),
                  ),
                ),
              ),
              ListWheelScrollView.useDelegate(
                controller: c,
                itemExtent: 44,
                perspective: 0.003,
                diameterRatio: 1.8,
                physics: const FixedExtentScrollPhysics(),
                onSelectedItemChanged: onPick,
                childDelegate: ListWheelChildLoopingListDelegate(
                  children: [for (var i = 0; i < count; i++) Center(child: Text(i.toString().padLeft(2, '0'), style: T.value))],
                ),
              ),
            ]),
          ),
        ]),
      );

  @override
  Widget build(BuildContext context) => Row(children: [
        _wheel(_h, 24, (i) {
          _hour = i % 24;
          widget.onChanged(_hour * 60 + _min);
        }, 'HOUR'),
        Padding(padding: const EdgeInsets.only(top: 18), child: Text(':', style: T.value)),
        _wheel(_m, 60, (i) {
          _min = i % 60;
          widget.onChanged(_hour * 60 + _min);
        }, 'MINUTE'),
      ]);
}

// ---------- viewfinder corners ----------

/// Four square corner marks around [child], like a camera viewfinder. For things that are "being watched" or captured.
class CornerBrackets extends StatelessWidget {
  const CornerBrackets({super.key, required this.child, this.color = C.text2, this.length = 14});
  final Widget child;
  final Color color;
  final double length;
  @override
  Widget build(BuildContext context) => CustomPaint(foregroundPainter: _BracketPainter(color, length), child: child);
}

class _BracketPainter extends CustomPainter {
  _BracketPainter(this.color, this.len);
  final Color color;
  final double len;
  @override
  void paint(Canvas c, Size s) {
    final p = strokePaint(color, 1.6);
    const m = 4.0;
    for (final (x, y, dx, dy) in [(m, m, 1.0, 1.0), (s.width - m, m, -1.0, 1.0), (m, s.height - m, 1.0, -1.0), (s.width - m, s.height - m, -1.0, -1.0)]) {
      c.drawLine(Offset(x, y), Offset(x + dx * len, y), p);
      c.drawLine(Offset(x, y), Offset(x, y + dy * len), p);
    }
  }

  @override
  bool shouldRepaint(_BracketPainter o) => o.color != color || o.len != len;
}
