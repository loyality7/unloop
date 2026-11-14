import 'package:flutter/widgets.dart';

import 'screens/onboarding.dart';
import 'screens/tabs.dart';
import 'state.dart';
import 'ui/tokens.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const UnloopApp());
}

/// Plain WidgetsApp on purpose: no Material theme or widgets anywhere.
class UnloopApp extends StatefulWidget {
  const UnloopApp({super.key});
  @override
  State<UnloopApp> createState() => _UnloopAppState();
}

class _UnloopAppState extends State<UnloopApp> {
  @override
  void initState() {
    super.initState();
    app.load();
  }

  @override
  Widget build(BuildContext context) => WidgetsApp(
        title: 'Unloop',
        color: C.bg,
        textStyle: T.body,
        debugShowCheckedModeBanner: false,
        pageRouteBuilder: <V>(settings, builder) => PageRouteBuilder<V>(settings: settings, pageBuilder: (c, _, _) => builder(c)),
        home: ListenableBuilder(
          listenable: app,
          builder: (_, _) {
            if (!app.loaded) return const ColoredBox(color: C.bg);
            return app.onboarded ? const Shell() : Onboarding(onDone: app.finishOnboarding);
          },
        ),
      );
}
