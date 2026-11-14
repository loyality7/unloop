import 'package:flutter_test/flutter_test.dart';
import 'package:unloop/native_bridge.dart';

void main() {
  test('limits round trip', () {
    final l = parseLimits(limitsToJson([const LimitRule('a', 60, warnPercents: [50, 80])]));
    expect(l.single.target, 'a');
    expect(l.single.warnPercents, [50, 80]);
  });

  test('schedules round trip', () {
    final s = parseSchedules(
        schedulesToJson([const ScheduleRule('s', true, ['MONDAY'], '22:00', '07:00')]));
    expect(s.single.end, '07:00');
    expect(s.single.days, ['MONDAY']);
  });

  test('health, import and counter parsing', () {
    expect(HealthStatus.parse('{}').healthy, true);
    final h = HealthStatus.parse('{"healthy":false,"faults":["USAGE_ACCESS_OFF"],"usageAccess":false}');
    expect(h.faults, ['USAGE_ACCESS_OFF']);
    final o = ImportOutcome.fromMap({'ok': true, 'limits': 2, 'skipped': ['x']});
    expect(o.limits, 2);
    expect(o.skipped, ['x']);
    expect(parseCounters('{"a":1}')['a'], 1);
  });

  test('scheduled apps wire format is a de-duplicated JSON array', () {
    expect(scheduledAppsToJson(['a.b', 'c.d', 'a.b', ' ']), '["a.b","c.d"]');
    expect(scheduledAppsToJson([]), '[]');
  });
}
