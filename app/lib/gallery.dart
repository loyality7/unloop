import 'package:flutter/widgets.dart';

import 'state.dart';
import 'ui/kit.dart';
import 'ui/tokens.dart';

/// Design system gallery: every token and component, interactive. Not a product screen.
class Gallery extends StatefulWidget {
  const Gallery({super.key});
  @override
  State<Gallery> createState() => _GalleryState();
}

class _GalleryState extends State<Gallery> {
  bool _t1 = true, _t2 = false;
  int _seg = 0, _mins = 90, _nav = 0;
  List<bool> _days = [true, true, true, true, true, false, false];
  final _domains = <String>['example-feed.com'];
  final _rounds = [true, false, false, true, false, false];
  int _accent = app.accentIndex;

  @override
  Widget build(BuildContext context) => Column(children: [
        Expanded(
          child: Screen(
            back: false,
            title: 'Unloop DS',
            subtitle: 'Dot-matrix numbers, mono labels, one red signal. Tap everything.',
            children: [
              // ---- stat tiles, the signature look ----
              Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                  child: Tile(
                    label: 'Stepped back',
                    index: '01',
                    child: Column(children: [
                      DotRing(progress: 0.68, size: 132, child: const DotText('42', pitch: 5)),
                      const SizedBox(height: S.x4),
                      Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: const [Metric('IG', 'Reels'), Metric('YT', 'Shorts')]),
                    ]),
                  ),
                ),
                const SizedBox(width: S.x3),
                Expanded(
                  child: Column(children: [
                    Tile(
                      light: true,
                      label: 'Protection',
                      trailing: const Dot(C.red),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        const Text('ON', style: T.value),
                        const SizedBox(height: S.x3),
                        Text('ACCESSIBILITY', style: T.label.copyWith(color: C.ink.withValues(alpha: 0.6), fontSize: 10)),
                      ]),
                    ),
                    const SizedBox(height: S.x3),
                    Tile(
                      label: 'Limit',
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        const Text('1H 22M', style: T.value),
                        const SizedBox(height: S.x3),
                        const DotMeter(value: 0.68, dots: 18),
                        const SizedBox(height: S.x2),
                        Text('OF 2H', style: T.label.copyWith(fontSize: 10, color: C.text3)),
                      ]),
                    ),
                  ]),
                ),
              ]),
              const SizedBox(height: S.x3),
              Tile(
                label: 'Screen time',
                index: '02',
                trailing: const Tag('Sample'),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const DotText('2H 45M', pitch: 5),
                  const SizedBox(height: S.x5),
                  const DotBars(values: [30, 55, 20, 80, 60, 40, 90], labels: ['M', 'T', 'W', 'T', 'F', 'S', 'S'], highlight: 6, alert: true),
                ]),
              ),
              const SizedBox(height: S.x3),
              Tile(
                label: "Today's schedule",
                child: Column(children: [
                  for (final r in const [('22:00', 'Wind down'), ('09:00', 'Work focus')])
                    Padding(
                      padding: const EdgeInsets.only(bottom: S.x3),
                      child: Row(children: [
                        Text(r.$1, style: T.mono),
                        const SizedBox(width: S.x5),
                        Expanded(child: Text(r.$2, style: T.body)),
                      ]),
                    ),
                  Row(mainAxisAlignment: MainAxisAlignment.end, children: [RoundBtn(Icons.add_rounded, on: true, size: 40, onTap: () {})]),
                ]),
              ),
              const SizedBox(height: S.x3),
              Tile(
                label: 'Quick controls',
                child: Wrap(spacing: S.x3, runSpacing: S.x3, children: [
                  for (var i = 0; i < _rounds.length; i++)
                    RoundBtn(const [Icons.timer_outlined, Icons.nights_stay_outlined, Icons.language_rounded, Icons.notifications_none_rounded, Icons.dns_outlined, Icons.shield_outlined][i], on: _rounds[i], onTap: () => setState(() => _rounds[i] = !_rounds[i])),
                ]),
              ),

              // ---- foundations ----
              const SectionLabel('Brand', index: '03'),
              Tile(child: Row(children: [
                const LoopMark(size: 52),
                const SizedBox(width: S.x5),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const Text('The open loop', style: T.heading),
                  const SizedBox(height: 2),
                  Text('A dot ring that never closes: the scroll you step out of.', style: T.small),
                ])),
              ])),
              const SectionLabel('Color', index: '04'),
              Wrap(spacing: S.x3, runSpacing: S.x3, children: const [
                _Swatch('bg', C.bg), _Swatch('surface', C.surface), _Swatch('raised', C.raised), _Swatch('line', C.line),
                _Swatch('fg', C.fg), _Swatch('light', C.light), _Swatch('text2', C.text2), _Swatch('text3', C.text3),
                _Swatch('dim', C.dim), _Swatch('red', C.red),
              ]),
              const SectionLabel('Accent', index: '04b'),
              Tile(child: ColorPick(index: _accent, onChanged: (i) { setState(() => _accent = i); app.setAccent(i); })),
              const SectionLabel('Type', index: '05'),
              Tile(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const DotText('09:35', pitch: 6),
                const SizedBox(height: S.x4),
                const Text('Value 24 mono', style: T.value),
                const SizedBox(height: S.x3),
                const Text('Title 24', style: T.title),
                const SizedBox(height: S.x3),
                const Text('Heading 17', style: T.heading),
                const SizedBox(height: S.x3),
                const Text('Body 15, the default reading size for most text.', style: T.body),
                const SizedBox(height: S.x3),
                const Text('Body muted 15 for supporting copy.', style: T.bodyMuted),
                const SizedBox(height: S.x3),
                const Text('Small 13 for hints and captions.', style: T.small),
                const SizedBox(height: S.x3),
                Text('LABEL 11 MONO CAPS', style: T.label),
              ])),
              const SectionLabel('Dot display', index: '06'),
              Tile(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: const [
                DotText('ABCDEFGHIJKLM', pitch: 3.6),
                SizedBox(height: S.x3),
                DotText('NOPQRSTUVWXYZ', pitch: 3.6),
                SizedBox(height: S.x3),
                DotText('0123456789 :.%-+', pitch: 3.6),
              ])),

              // ---- controls ----
              const SectionLabel('Buttons', index: '07'),
              Btn('Primary', onTap: () {}),
              const SizedBox(height: S.x3),
              Btn('Secondary', kind: BtnKind.secondary, onTap: () {}),
              const SizedBox(height: S.x3),
              Btn('With icon', icon: Icons.shield_outlined, onTap: () {}),
              const SizedBox(height: S.x3),
              Btn('Ghost', kind: BtnKind.ghost, onTap: () {}),
              const SizedBox(height: S.x3),
              Btn('Delete all data', kind: BtnKind.danger, icon: Icons.delete_outline_rounded, onTap: () {}),
              const SizedBox(height: S.x3),
              const Btn('Disabled'),
              const SizedBox(height: S.x3),
              Row(children: [
                Btn('Small', small: true, expand: false, onTap: () {}),
                const SizedBox(width: S.x2),
                Btn('Small', small: true, expand: false, kind: BtnKind.secondary, onTap: () {}),
                const SizedBox(width: S.x2),
                Btn('Small', small: true, expand: false, kind: BtnKind.ghost, onTap: () {}),
              ]),
              const SectionLabel('Round buttons', index: '08'),
              Row(children: [
                const RoundBtn(Icons.wifi_rounded, on: true),
                const SizedBox(width: S.x3),
                const RoundBtn(Icons.bluetooth_rounded),
                const SizedBox(width: S.x3),
                const RoundBtn(Icons.flashlight_on_outlined),
                const SizedBox(width: S.x3),
                RoundBtn(Icons.play_arrow_rounded, onTap: () {}),
              ]),
              const SectionLabel('Toggle', index: '09'),
              Group([
                Row2(title: 'On', sub: 'White track, ink knob', trailing: Toggle(value: _t1, onChanged: (v) => setState(() => _t1 = v))),
                Row2(title: 'Off', sub: 'Dark track, grey knob', trailing: Toggle(value: _t2, onChanged: (v) => setState(() => _t2 = v))),
                const Row2(title: 'Disabled', trailing: Toggle(value: true)),
              ]),
              const SectionLabel('Rows', index: '10'),
              Group([
                const Row2(icon: Icons.block_rounded, iconTone: C.red, title: 'Icon, title, subtitle', sub: 'Round icon badge, tone for alerts'),
                Row2(icon: Icons.timer_outlined, title: 'With arrow', sub: 'Navigates somewhere', chevron: true, onTap: () {}),
                const Row2(icon: Icons.hourglass_bottom_rounded, title: 'With value', trailing: Text('48M', style: T.value)),
                Row2(title: 'No icon', trailing: const Tag('Soon'), onTap: () {}),
              ]),
              const SectionLabel('Tiles', index: '11'),
              const Tile(child: Text('A plain tile. 24 radius, no border, no shadow.', style: T.body)),
              const SizedBox(height: S.x3),
              const Tile(light: true, label: 'Light tile', child: Text('For emphasis. Use once per screen.', style: TextStyle(fontFamily: 'Roboto', fontSize: 15, color: C.ink))),
              const SizedBox(height: S.x3),
              Tile(
                onTap: () {},
                label: 'Tappable',
                trailing: const Tag('On', tone: C.fg, filled: true),
                child: Row(children: [
                  const IconBadge(Icons.camera_alt_rounded, size: 48),
                  const SizedBox(width: S.x4),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    const Text('Instagram', style: T.heading),
                    Text('Reels', style: T.small),
                  ])),
                ]),
              ),
              const SectionLabel('Tags and dots', index: '12'),
              Wrap(spacing: S.x2, runSpacing: S.x2, crossAxisAlignment: WrapCrossAlignment.center, children: const [
                Tag('Neutral'), Tag('Active', tone: C.fg), Tag('Active', tone: C.fg, filled: true), Tag('Limit', tone: C.red), Tag('Live', tone: C.red, filled: true),
                Dot(C.fg), Dot(C.red), Dot(C.text3),
              ]),
              const SectionLabel('Notices', index: '13'),
              const Notice('Information. Quiet, neutral.'),
              const SizedBox(height: S.x3),
              Notice('Needs attention, with an action.', warn: true, action: 'Fix', onAction: () {}),
              const SectionLabel('Segmented', index: '14'),
              Segmented(options: const ['Today', 'Week', 'Month'], index: _seg, onChanged: (v) => setState(() => _seg = v)),
              const SectionLabel('Day picker', index: '15'),
              DayPicker(days: _days, onChanged: (v) => setState(() => _days = v)),
              const SectionLabel('Stepper', index: '16'),
              Tile(child: Row(children: [
                const Expanded(child: Text('Daily limit', style: T.body)),
                Stepper2(value: _mins, onChanged: (v) => setState(() => _mins = v), format: (m) => '${m ~/ 60}H${(m % 60).toString().padLeft(2, '0')}'),
              ])),
              const SectionLabel('Text field', index: '17'),
              Field(hint: 'Add a domain', icon: Icons.add_rounded, onSubmit: (v) => setState(() => _domains.add(v))),
              const SizedBox(height: S.x3),
              Group([for (final d in _domains) Row2(icon: Icons.language_rounded, title: d, trailing: RoundBtn(Icons.close_rounded, size: 32, onTap: () => setState(() => _domains.remove(d))))]),
              const SectionLabel('Meters', index: '18'),
              const Tile(child: Column(children: [
                DotMeter(value: 0.4),
                SizedBox(height: S.x4),
                DotMeter(value: 0.8),
                SizedBox(height: S.x4),
                DotMeter(value: 1),
              ])),
              const SectionLabel('Dock', index: '19'),
              BottomNav(
                items: const [NavItem(Icons.home_rounded, 'Home'), NavItem(Icons.apps_rounded, 'Apps'), NavItem(Icons.bar_chart_rounded, 'Insights'), NavItem(Icons.settings_rounded, 'Settings')],
                index: _nav,
                onChanged: (v) => setState(() => _nav = v),
              ),
              const SizedBox(height: S.x8),
            ],
          ),
        ),
      ]);
}

class _Swatch extends StatelessWidget {
  const _Swatch(this.name, this.color);
  final String name;
  final Color color;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: 96,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(height: 52, decoration: BoxDecoration(color: color, borderRadius: R.of(R.sm), border: Border.all(color: C.line))),
          const SizedBox(height: 6),
          Text(name.toUpperCase(), style: T.label.copyWith(fontSize: 10)),
        ]),
      );
}
