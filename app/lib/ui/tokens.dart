import 'package:flutter/widgets.dart';

/// Unloop design tokens. Monochrome, dot-matrix, one red signal. Dark only for now.
class C {
  static const bg = Color(0xFF0A0A0A);
  static const surface = Color(0xFF141414); // cards
  static const raised = Color(0xFF1E1E1E); // buttons, wells
  static const line = Color(0xFF2B2B2B);
  static const fg = Color(0xFFFFFFFF); // primary content, active states
  static const ink = Color(0xFF0A0A0A); // content on white
  static const light = Color(0xFFF0F0F0); // light card
  static const text = Color(0xFFF2F2F2);
  static const text2 = Color(0xFFA0A0A0);
  static const text3 = Color(0xFF666666);
  static const dim = Color(0xFF2A2A2A); // unlit dots
  /// Default signal color AND a sentinel: widgets pass `C.red` and leaf widgets resolve it
  /// through [res] to the user's chosen accent. Never read `red` directly when painting; use [signal].
  static const red = Color(0xFFFF3B2F);
  static Color signal = red; // the user's accent: live, warning, limit
  static Color res(Color c) => c == red ? signal : c;

  static const palette = <(String, Color)>[
    ('Red', Color(0xFFFF3B2F)),
    ('Orange', Color(0xFFFF8A2B)),
    ('Amber', Color(0xFFFFC53D)),
    ('Lime', Color(0xFFB6F23A)),
    ('Mint', Color(0xFF4DE3B0)),
    ('Cyan', Color(0xFF3DD6FF)),
    ('Blue', Color(0xFF4D7CFF)),
    ('Violet', Color(0xFFA77BFF)),
    ('Pink', Color(0xFFFF5FA2)),
    ('White', Color(0xFFFFFFFF)),
  ];
}

class S {
  static const double x1 = 4, x2 = 8, x3 = 12, x4 = 16, x5 = 20, x6 = 24, x8 = 32, x10 = 40;
  static const double gutter = 20;
}

class R {
  static const double sm = 14, md = 24, lg = 32;
  static const pill = BorderRadius.all(Radius.circular(999));
  static BorderRadius of(double r) => BorderRadius.all(Radius.circular(r));
}

class T {
  static const _sans = 'Roboto';
  static const _mono = 'monospace'; // ponytail: system mono; bundle a dot/mono typeface (offline) later
  static const title = TextStyle(fontFamily: _sans, fontSize: 24, height: 1.2, fontWeight: FontWeight.w500, letterSpacing: -0.4, color: C.text);
  static const heading = TextStyle(fontFamily: _sans, fontSize: 17, height: 1.3, fontWeight: FontWeight.w500, color: C.text);
  static const body = TextStyle(fontFamily: _sans, fontSize: 15, height: 1.45, fontWeight: FontWeight.w400, color: C.text);
  static const bodyMuted = TextStyle(fontFamily: _sans, fontSize: 15, height: 1.45, fontWeight: FontWeight.w400, color: C.text2);
  static const small = TextStyle(fontFamily: _sans, fontSize: 13, height: 1.4, fontWeight: FontWeight.w400, color: C.text2);
  static const button = TextStyle(fontFamily: _sans, fontSize: 15, height: 1.2, fontWeight: FontWeight.w600, color: C.text);
  // mono caps: labels, metadata, units
  static const label = TextStyle(fontFamily: _mono, fontSize: 11, height: 1.2, fontWeight: FontWeight.w500, letterSpacing: 1.6, color: C.text2);
  static const mono = TextStyle(fontFamily: _mono, fontSize: 13, height: 1.3, fontWeight: FontWeight.w400, letterSpacing: 0.4, color: C.text);
  static const value = TextStyle(fontFamily: _mono, fontSize: 24, height: 1.1, fontWeight: FontWeight.w500, letterSpacing: 1, color: C.text);
}
