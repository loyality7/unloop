import 'dart:async';

import 'package:flutter/widgets.dart';

import '../state.dart';
import 'kit.dart';
import 'tokens.dart';

/// Seconds the user must wait before loosening their own protection.
const frictionSeconds = 60;

/// Passing once unlocks the same kind of change for a few minutes, so tuning several things is not a chore.
const _passMs = 3 * 60 * 1000;
final Map<String, int> _passedAt = {};

const _quotes = [
  'This urge will pass. You do not have to act on it.',
  'Cravings peak and fade within minutes. Let this one fade.',
  'You set this up when you were thinking clearly. Trust that person.',
  'Boredom is not an emergency.',
  'What were you about to do before you picked up your phone?',
  'A pause is a vote for the life you want.',
  'One more reel is never one more reel.',
  'Notice the urge. Breathe. Let it go by.',
  'Ten slow breaths cost less than an hour lost.',
  'Future you is watching what you do next.',
];

/// Makes loosening feel like what it is. Resolves true after a 60 second wait the user chooses to sit through,
/// false if they back out. Skipped while strict mode is on: strict mode refuses the change itself and says why.
Future<bool> askFriction(BuildContext context, {required String title, required String key, String? detail}) async {
  if (app.strict) return true;
  final now = DateTime.now().millisecondsSinceEpoch;
  final last = _passedAt[key];
  if (last != null && now - last < _passMs) return true;
  final ok = await go<bool>(context, _FrictionScreen(title: title, detail: detail)) ?? false;
  if (ok) _passedAt[key] = DateTime.now().millisecondsSinceEpoch;
  return ok;
}

// ---- guarded versions of every action that loosens protection ----

Future<void> setSurfaceChecked(BuildContext context, String id, bool on, String name) async {
  if (!on && !await askFriction(context, title: 'Turn off $name?', key: 'surface')) return;
  await app.setSurface(id, on);
}

Future<void> pauseChecked(BuildContext context, int minutes) async {
  if (!await askFriction(context, title: 'Pause Unloop?', key: 'pause')) return;
  await app.pause(minutes);
}

class _FrictionScreen extends StatefulWidget {
  const _FrictionScreen({required this.title, this.detail});
  final String title;
  final String? detail;
  @override
  State<_FrictionScreen> createState() => _FrictionScreenState();
}

class _FrictionScreenState extends State<_FrictionScreen> {
  int _left = frictionSeconds;
  late int _quote = DateTime.now().second % _quotes.length;
  Timer? _t;

  @override
  void initState() {
    super.initState();
    _t = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {
        _left--;
        if ((frictionSeconds - _left) % 10 == 0) _quote = (_quote + 1) % _quotes.length;
        if (_left <= 0) _t?.cancel();
      });
    });
  }

  @override
  void dispose() {
    _t?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Screen(
        title: widget.title,
        subtitle: widget.detail ?? 'Take a minute before you loosen your own rules.',
        children: [
          const SizedBox(height: S.x4),
          Center(
            child: SizedBox(
              width: 300,
              height: 300,
              child: Stack(alignment: Alignment.center, children: [
                const DotRipple(size: 300),
                DotRing(
                  progress: (frictionSeconds - _left.clamp(0, frictionSeconds)) / frictionSeconds,
                  size: 180,
                  child: DotText('${_left.clamp(0, frictionSeconds)}', pitch: 6),
                ),
              ]),
            ),
          ),
          const SizedBox(height: S.x6),
          Tile(child: Text(_quotes[_quote], style: T.heading, textAlign: TextAlign.center)),
          const SizedBox(height: S.x6),
          Btn('Keep my protection', icon: Icons.shield_outlined, onTap: () => Navigator.of(context).pop(false)),
          const SizedBox(height: S.x3),
          Btn(
            _left > 0 ? 'Wait ${_left}s' : 'Turn it off anyway',
            kind: BtnKind.danger,
            onTap: _left > 0 ? null : () => Navigator.of(context).pop(true),
          ),
        ],
      );
}
