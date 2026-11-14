import 'package:flutter/widgets.dart';

import '../state.dart';
import '../ui/app_icon.dart';
import '../ui/friction.dart';
import '../ui/kit.dart';
import '../ui/tokens.dart';

/// "Groups" block on the Limits screen: several apps sharing one daily limit.
class GroupsSection extends StatelessWidget {
  const GroupsSection({super.key, required this.base});

  /// Each group's limit when the Limits screen opened, so going back up to it is free (only going above asks to wait).
  final Map<String, int> base;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          for (final g in app.groups) {
            base.putIfAbsent(g.id, () => g.minutes);
          }
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const SectionLabel('Groups', index: '01'),
            if (app.groups.isEmpty)
              const Tile(child: Text('One daily limit shared by several apps, for example Instagram, TikTok and X together.', style: T.bodyMuted)),
            for (final g in app.groups) ...[
              _GroupCard(g, base),
              const SizedBox(height: S.x3),
            ],
            Btn('New group', kind: BtnKind.secondary, icon: Icons.add_rounded, onTap: () {
              final g = app.newGroup();
              go(context, GroupScreen(g.id));
            }),
          ]);
        },
      );
}

class _GroupCard extends StatelessWidget {
  const _GroupCard(this.g, this.base);
  final AppGroup g;
  final Map<String, int> base;

  @override
  Widget build(BuildContext context) {
    final usedMin = g.pkgs.fold<int>(0, (a, p) => a + (app.usage[p] ?? 0)) ~/ 60;
    final shown = g.pkgs.take(6).toList();
    return Tile(
      label: g.name,
      onTap: () => go(context, GroupScreen(g.id)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (g.pkgs.isEmpty)
          const Text('No apps yet. Tap to add some.', style: T.small)
        else
          Row(children: [
            for (final p in shown) Padding(padding: const EdgeInsets.only(right: S.x2), child: AppIcon(p, size: 32)),
            if (g.pkgs.length > shown.length) Text('+${g.pkgs.length - shown.length}', style: T.small),
          ]),
        const SizedBox(height: S.x4),
        Text('${dur(usedMin)} / ${dur(g.minutes)}'.toUpperCase(), style: T.value),
        const SizedBox(height: S.x4),
        DotMeter(value: g.minutes == 0 ? 1.0 : (usedMin / g.minutes).clamp(0.0, 1.0)),
        const SizedBox(height: S.x5),
        Row(children: [
          Expanded(child: Text('SHARED DAILY LIMIT', style: T.label)),
          Stepper2(
            value: g.minutes,
            step: 15,
            max: app.strict ? g.minutes : 480,
            format: (m) => dur(m).toUpperCase(),
            onChanged: (v) async {
              final b = base[g.id] ?? g.minutes;
              if (v > b && !await askFriction(context, title: 'Raise this group limit?', key: 'limit-raise')) return;
              if (v > b) base[g.id] = v;
              g.minutes = v;
              app.saveGroups();
            },
          ),
        ]),
      ]),
    );
  }
}

class GroupScreen extends StatefulWidget {
  const GroupScreen(this.id, {super.key});
  final String id;
  @override
  State<GroupScreen> createState() => _GroupScreenState();
}

class _GroupScreenState extends State<GroupScreen> {
  String _q = '';
  late final int _startLimit = app.groups.where((x) => x.id == widget.id).firstOrNull?.minutes ?? 60;
  late int _base = _startLimit;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) {
          final g = app.groups.where((x) => x.id == widget.id).firstOrNull;
          if (g == null) return const Screen(title: 'Group', children: [Tile(child: Text('This group no longer exists.', style: T.bodyMuted))]);
          final all = [...app.installed]..sort((a, b) {
              final am = g.pkgs.contains(a.pkg) ? 0 : 1, bm = g.pkgs.contains(b.pkg) ? 0 : 1;
              return am != bm ? am.compareTo(bm) : a.label.toLowerCase().compareTo(b.label.toLowerCase());
            });
          final list = [for (final a in all) if (a.label.toLowerCase().contains(_q)) a];
          return Screen(title: g.name, subtitle: 'These apps share one daily limit.', children: [
            Field(hint: 'Rename group', icon: Icons.edit_outlined, onSubmit: (v) {
              g.name = v;
              app.saveGroups();
            }),
            const SizedBox(height: S.x3),
            Tile(
              label: 'Shared daily limit',
              child: Row(children: [
                Expanded(child: Text('PER DAY, ALL APPS TOGETHER', style: T.label)),
                Stepper2(
                  value: g.minutes,
                  step: 15,
                  max: app.strict ? g.minutes : 480,
                  format: (m) => dur(m).toUpperCase(),
                  onChanged: (v) async {
                    if (v > _base && !await askFriction(context, title: 'Raise this group limit?', key: 'limit-raise')) return;
                    if (v > _base) _base = v;
                    g.minutes = v;
                    app.saveGroups();
                  },
                ),
              ]),
            ),
            const SectionLabel('Apps in this group', index: '01'),
            Field(hint: 'Search apps', onChanged: (v) => setState(() => _q = v.trim().toLowerCase())),
            const SizedBox(height: S.x3),
            if (list.isEmpty)
              const Tile(child: Text('No apps found.', style: T.bodyMuted))
            else
              Group([
                for (final a in list.take(120))
                  Row2(
                    leading: AppIcon(a.pkg),
                    title: a.label,
                    trailing: Toggle(
                      value: g.pkgs.contains(a.pkg),
                      onChanged: (v) async {
                        if (!v && !await askFriction(context, title: 'Take ${a.label} out of the group?', key: 'group-member')) return;
                        v ? g.pkgs.add(a.pkg) : g.pkgs.remove(a.pkg);
                        app.saveGroups();
                      },
                    ),
                  ),
              ]),
            const SizedBox(height: S.x6),
            Btn('Remove group', kind: BtnKind.danger, icon: Icons.delete_outline_rounded, onTap: () async {
              if (!await askFriction(context, title: 'Remove this group?', key: 'group-remove')) return;
              await app.removeGroup(g);
              if (context.mounted) Navigator.of(context).maybePop();
            }),
          ]);
        },
      );
}
