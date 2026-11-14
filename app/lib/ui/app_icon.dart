import 'package:flutter/widgets.dart';

import '../state.dart';
import 'tokens.dart';

/// The app's real launcher icon. While it loads, or when the app is not installed, a neutral letter badge shows,
/// never an unrelated glyph.
class AppIcon extends StatelessWidget {
  const AppIcon(this.pkg, {super.key, this.size = 40});
  final String pkg;
  final double size;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final bytes = app.iconOf(pkg);
          final radius = BorderRadius.circular(size * 0.24);
          if (bytes != null) {
            return ClipRRect(
              borderRadius: radius,
              child: Image.memory(bytes, width: size, height: size, fit: BoxFit.cover, gaplessPlayback: true, filterQuality: FilterQuality.medium),
            );
          }
          final name = app.appName(pkg);
          final letter = (name.isEmpty ? '?' : name.characters.first).toUpperCase();
          return Container(
            width: size,
            height: size,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: C.raised, borderRadius: radius),
            child: Text(letter, style: T.heading.copyWith(fontSize: size * 0.45, color: C.text2)),
          );
        },
      );
}
