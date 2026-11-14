import 'package:flutter/widgets.dart';

import '../state.dart';
import 'controls.dart';
import '../ui/kit.dart';
import '../ui/tokens.dart';

class Onboarding extends StatefulWidget {
  const Onboarding({super.key, required this.onDone});
  final VoidCallback onDone;
  @override
  State<Onboarding> createState() => _OnboardingState();
}

class _OnboardingState extends State<Onboarding> with WidgetsBindingObserver {
  final _pc = PageController();
  int _i = 0;
  static const _last = 3;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pc.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) app.refresh();
  }

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: C.bg,
        child: SafeArea(
          child: Column(children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(S.gutter, S.x4, S.gutter, 0),
              child: Row(children: [
                const LoopMark(size: 32),
              ]),
            ),
            Expanded(
              child: PageView(
                controller: _pc,
                physics: const BouncingScrollPhysics(),
                onPageChanged: (v) => setState(() => _i = v),
                children: const [_Welcome(), _How(), _Privacy(), _Access()],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(S.gutter, S.x2, S.gutter, S.x6),
              child: Column(children: [
                Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                  for (var d = 0; d <= _last; d++)
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 220),
                      margin: const EdgeInsets.symmetric(horizontal: 3),
                      width: 6,
                      height: 6,
                      decoration: BoxDecoration(color: d == _i ? C.fg : C.dim, shape: BoxShape.circle),
                    ),
                ]),
                const SizedBox(height: S.x6),
                if (_i < _last)
                  Btn('Continue', onTap: () => _pc.nextPage(duration: const Duration(milliseconds: 320), curve: Curves.easeOutCubic))
                else
                  ListenableBuilder(
                    listenable: app,
                    builder: (_, _) => Column(children: [
                      if (!app.perm.blockingActive) ...[
                        Btn('Turn on selective blocking', icon: Icons.shield_outlined, onTap: () => openAccessibilityWithConsent(context)),
                        const SizedBox(height: S.x2),
                        Btn('Set up later', kind: BtnKind.ghost, onTap: widget.onDone),
                      ] else
                        Btn('Start using Unloop', onTap: widget.onDone),
                    ]),
                  ),
              ]),
            ),
          ]),
        ),
      );
}

class _Slide extends StatefulWidget {
  const _Slide({required this.lines, required this.body, required this.child});
  final List<String> lines;
  final String body;
  final Widget child;
  @override
  State<_Slide> createState() => _SlideState();
}

class _SlideState extends State<_Slide> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1900));

  @override
  void initState() {
    super.initState();
    _c.forward();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  /// The display lights up during the first part of the animation; the text below fades in as it finishes.
  late final Animation<double> _board = CurvedAnimation(parent: _c, curve: const Interval(0, 0.7, curve: Curves.linear));

  @override
  Widget build(BuildContext context) {
    // Respect the system "remove animations" setting: show the finished state at once.
    if (MediaQuery.maybeOf(context)?.disableAnimations ?? false) _c.value = 1;
    final rest = CurvedAnimation(parent: _c, curve: const Interval(0.62, 1, curve: Curves.easeOut));
    return ListView(
      padding: const EdgeInsets.fromLTRB(S.gutter, S.x8, S.gutter, S.x4),
      physics: const BouncingScrollPhysics(),
      children: [
        DotBoard(lines: widget.lines, reveal: _board),
        const SizedBox(height: S.x4),
        FadeTransition(
          opacity: rest,
          child: SlideTransition(
            position: Tween(begin: const Offset(0, 0.04), end: Offset.zero).animate(rest),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(widget.body, style: T.bodyMuted),
              const SizedBox(height: S.x8),
              widget.child,
            ]),
          ),
        ),
      ],
    );
  }
}

class _Welcome extends StatelessWidget {
  const _Welcome();
  @override
  Widget build(BuildContext context) => const _Slide(
        lines: ['USE THE APPS', 'SKIP THE LOOP'],
        body: 'Unloop quietly steps you out of Reels and Shorts while everything you came for keeps working.',
        child: Group([
          Row2(icon: Icons.close_rounded, iconTone: C.red, title: 'Skipped', sub: 'Reels, Shorts, For You, Spotlight'),
          Row2(icon: Icons.check_rounded, title: 'Kept', sub: 'Messages, stories, search, uploads'),
          Row2(icon: Icons.check_rounded, title: 'Kept', sub: 'Profiles, subscriptions, comments'),
        ]),
      );
}

class _How extends StatelessWidget {
  const _How();
  @override
  Widget build(BuildContext context) => const _Slide(
        lines: ['CALM', 'NOT STRICT'],
        body: 'When a distracting screen opens, Unloop steps back for you. No lectures, no lock screens.',
        child: Group([
          Row2(icon: Icons.visibility_outlined, title: 'It notices', sub: 'Detects the Reels or Shorts screen on your phone'),
          Row2(icon: Icons.undo_rounded, title: 'It steps back', sub: 'Takes you to where you were'),
          Row2(icon: Icons.tune_rounded, title: 'You decide', sub: 'Choose what to skip, per app, any time'),
        ]),
      );
}

class _Privacy extends StatelessWidget {
  const _Privacy();
  @override
  Widget build(BuildContext context) => const _Slide(
        lines: ['PRIVATE', 'BY DESIGN'],
        body: 'Everything happens on this phone. Nothing to sign up for and nowhere for your data to go.',
        child: Group([
          Row2(icon: Icons.wifi_off_rounded, title: 'No internet permission', sub: 'The app cannot send data even if it wanted to'),
          Row2(icon: Icons.person_off_outlined, title: 'No account', sub: 'No sign-up, no profile, no tracking'),
          Row2(icon: Icons.code_rounded, title: 'Open source', sub: 'Free forever. Read every line'),
        ]),
      );
}

class _Access extends StatelessWidget {
  const _Access();
  @override
  Widget build(BuildContext context) => _Slide(
        lines: const ['ONE PERMISSION', 'TO START'],
        body: 'Unloop needs Android’s Accessibility service to see which screen is open. It is the only way to tell Reels from Messages.',
        child: Column(children: [
          const Group([
            Row2(icon: Icons.lock_outline_rounded, title: 'Checked on-device only', sub: 'Screen content is never stored or sent'),
            Row2(icon: Icons.block_rounded, title: 'Does not read what you type', sub: 'No passwords, no messages'),
            Row2(
              icon: Icons.account_balance_wallet_outlined,
              title: 'Payment apps stay untouched',
              sub: 'Unloop never reads payment or banking apps. Some of them warn while any Accessibility service is on',
            ),
          ]),
          const SizedBox(height: S.x4),
          ListenableBuilder(
            listenable: app,
            builder: (_, _) => app.perm.blockingActive
                ? const Notice('Selective blocking is on.')
                : const Notice('On some phones, first allow restricted settings in App info, then turn Unloop on under Accessibility.', warn: true),
          ),
        ]),
      );
}
