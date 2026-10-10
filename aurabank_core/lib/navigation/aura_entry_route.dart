import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../services/device_storage.dart';
import '../theme/aura_theme.dart';
import '../widgets/aura_logo.dart';
import '../widgets/aurora_background.dart';

/// Post-login welcome sequence, played once on the way into the dashboard.
///
///  1. An aurora sky blooms out of the Sign in button and covers the login.
///  2. The Aura mark lands in the centre with two ripples and a soft glow.
///  3. "Welcome back, `name`" rises word by word over a filling session bar.
///  4. The sky lifts away as a liquid curtain; the mark and greeting drift up
///     faster than the curtain, and the dashboard settles in underneath.
///
/// Reduced motion gets a plain cross-fade.
Route<T> auraEntryRoute<T>(RouteSettings settings, WidgetBuilder builder) {
  return PageRouteBuilder<T>(
    settings: settings,
    transitionDuration: const Duration(milliseconds: 2600),
    reverseTransitionDuration: const Duration(milliseconds: 300),
    pageBuilder: (context, _, __) => builder(context),
    transitionsBuilder: (context, animation, _, child) {
      if (AuraMotion.reduced(context) || animation.status == AnimationStatus.reverse) {
        return FadeTransition(opacity: animation, child: child);
      }
      return _WelcomeSequence(animation: animation, child: child);
    },
  );
}

/// Progress of [t] through the window [a, b], eased.
double _seg(double t, double a, double b, [Curve curve = Curves.easeOutCubic]) =>
    curve.transform(((t - a) / (b - a)).clamp(0.0, 1.0));

class _WelcomeSequence extends StatelessWidget {
  const _WelcomeSequence({required this.animation, required this.child});

  final Animation<double> animation;
  final Widget child;

  // Phase boundaries on the 0..1 timeline.
  static const _lift = 0.66;

  @override
  Widget build(BuildContext context) {
    final rawName = DeviceStorage.getLastLoginName()?.trim();
    final name = (rawName == null || rawName.isEmpty) ? null : rawName.split(' ').first;

    // Built once; only transforms change per frame.
    final sky = AuroraBackground(
      intensity: 1,
      period: const Duration(seconds: 6),
      child: const SizedBox.expand(),
    );

    return AnimatedBuilder(
      animation: animation,
      builder: (context, _) {
        final t = animation.value;
        final size = MediaQuery.sizeOf(context);

        final bloom = _seg(t, 0, 0.24, Curves.easeInOutCubic);
        final lift = _seg(t, _lift, 1, Curves.easeInOutQuart);

        // Dashboard: hidden under the sky, then settles up and in.
        final dash = _seg(t, _lift + 0.04, 1);
        // Not tappable (or visible to screen readers) until the sky has lifted.
        final dashboard = IgnorePointer(
          ignoring: t < 1,
          child: ExcludeSemantics(
            excluding: t < _lift,
            child: Opacity(
              opacity: dash,
              child: Transform.translate(
                offset: Offset(0, 48 * (1 - dash)),
                child: Transform.scale(scale: 0.94 + 0.06 * dash, child: child),
              ),
            ),
          ),
        );

        return Stack(
          fit: StackFit.expand,
          children: [
            dashboard,
            if (lift < 1)
              ClipPath(
                clipper: _SkyClipper(bloom: bloom, lift: lift, size: size),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    sky,
                    _WelcomeContent(t: t, lift: lift, name: name),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}

class _WelcomeContent extends StatelessWidget {
  const _WelcomeContent({required this.t, required this.lift, required this.name});

  final double t;
  final double lift;
  final String? name;

  @override
  Widget build(BuildContext context) {
    final land = _seg(t, 0.14, 0.38, Curves.easeOutBack);
    final glow = _seg(t, 0.16, 0.5);
    // The seal: an arc traces around the mark, then closes with a pop.
    final seal = _seg(t, 0.32, 0.58, Curves.easeInOutCubic);
    final sealed = _seg(t, 0.58, 0.68);
    final pop = math.sin(math.pi * sealed) * 0.08;
    // A band of light sweeps across the greeting once the seal closes.
    final shine = _seg(t, 0.58, 0.76, Curves.easeInOut) * 1.6 - 0.3;
    final statusIn = _seg(t, 0.30, 0.38);
    final securing = statusIn * (1 - _seg(t, 0.56, 0.60));
    final done = _seg(t, 0.59, 0.65);

    // Content leaves faster than the curtain for a parallax lift.
    final exitY = -160 * lift;
    final exitFade = 1 - _seg(lift, 0, 0.55);

    final words = <String>['Welcome', 'back${name == null ? '' : ','}'];

    // Text inside a route transition has no Material above it; without this
    // Flutter falls back to its debug style (yellow double underline).
    return Material(
      type: MaterialType.transparency,
      child: Opacity(
        opacity: exitFade,
        child: Transform.translate(
          offset: Offset(0, exitY),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox.square(
                  dimension: 200,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      // Soft glow that breathes in behind the mark.
                      Container(
                        width: 200,
                        height: 200,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: RadialGradient(colors: [
                            AuraColors.mint.withValues(alpha: 0.35 * glow),
                            AuraColors.sky.withValues(alpha: 0.12 * glow),
                            Colors.transparent,
                          ]),
                        ),
                      ),
                      CustomPaint(
                        size: const Size.square(200),
                        painter: _RipplePainter(t),
                      ),
                      CustomPaint(
                        size: const Size.square(200),
                        painter: _SealPainter(seal: seal, sealed: sealed),
                      ),
                      Transform.scale(
                        scale: 0.3 + 0.7 * land + pop,
                        child: Transform.rotate(
                          angle: (1 - land) * -0.5,
                          child: Opacity(
                            opacity: land.clamp(0.0, 1.0),
                            child: const AuraLogo(size: 76, style: AuraLogoStyle.white, borderRadius: 22),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 8),
                ShaderMask(
                  blendMode: BlendMode.srcATop,
                  shaderCallback: (r) => LinearGradient(
                    begin: const Alignment(-1, -0.4),
                    end: const Alignment(1, 0.4),
                    colors: [
                      Colors.transparent,
                      Colors.white.withValues(alpha: 0.9),
                      Colors.transparent,
                    ],
                    stops: [
                      (shine - 0.15).clamp(0.0, 1.0),
                      shine.clamp(0.0, 1.0),
                      (shine + 0.15).clamp(0.0, 1.0),
                    ],
                  ).createShader(r),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Wrap(
                        alignment: WrapAlignment.center,
                        spacing: 10,
                        children: [
                          for (var i = 0; i < words.length; i++)
                            _Rise(
                              p: _seg(t, 0.26 + i * 0.05, 0.44 + i * 0.05),
                              child: Text(words[i], style: _headline(Colors.white)),
                            ),
                        ],
                      ),
                      if (name != null)
                        _Rise(
                          p: _seg(t, 0.38, 0.56),
                          child: ShaderMask(
                            shaderCallback: (r) => const LinearGradient(
                              colors: [AuraColors.mint, AuraColors.sky],
                            ).createShader(r),
                            child: Text(name!, style: _headline(Colors.white)),
                          ),
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: 22),
                // Status swaps in place: the old line drops away, the new one rises.
                SizedBox(
                  height: 24,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      Opacity(
                        opacity: securing,
                        child: Transform.translate(
                          offset: Offset(0, 8 * _seg(t, 0.56, 0.60)),
                          child: Text('Securing your session', style: _status),
                        ),
                      ),
                      _Rise(
                        p: done,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.check_circle_rounded, size: 16, color: AuraColors.mint.withValues(alpha: 0.9)),
                            const SizedBox(width: 6),
                            Text('You\'re in', style: _status.copyWith(color: Colors.white.withValues(alpha: 0.85))),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  static final _status = TextStyle(
    fontSize: 13,
    letterSpacing: 0.3,
    color: Colors.white.withValues(alpha: 0.6),
  );

  static TextStyle _headline(Color color) => TextStyle(
        fontSize: 34,
        height: 1.15,
        fontWeight: FontWeight.w600,
        letterSpacing: -1,
        color: color,
      );
}

/// Slides a child up from below a mask line while it fades in.
class _Rise extends StatelessWidget {
  const _Rise({required this.p, required this.child});

  final double p;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: Opacity(
        opacity: p,
        child: Transform.translate(offset: Offset(0, 36 * (1 - p)), child: child),
      ),
    );
  }
}

/// Two rings that pulse outward as the seal closes.
class _RipplePainter extends CustomPainter {
  const _RipplePainter(this.t);

  final double t;

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    for (final start in const [0.58, 0.63]) {
      final p = _seg(t, start, start + 0.2);
      if (p <= 0 || p >= 1) continue;
      canvas.drawCircle(
        c,
        40 + 60 * p,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5 * (1 - p) + 0.5
          ..color = AuraColors.mint.withValues(alpha: 0.7 * (1 - p)),
      );
    }
  }

  @override
  bool shouldRepaint(_RipplePainter old) => old.t != t;
}

/// A gradient arc that traces a full turn around the mark with a glowing
/// head, then bursts outward and fades once the circle closes.
class _SealPainter extends CustomPainter {
  const _SealPainter({required this.seal, required this.sealed});

  final double seal;
  final double sealed;

  @override
  void paint(Canvas canvas, Size size) {
    if (seal <= 0 || sealed >= 1) return;
    final c = size.center(Offset.zero);
    final r = 58 + 22 * Curves.easeOut.transform(sealed);
    final fade = 1 - sealed;
    final rect = Rect.fromCircle(center: c, radius: r);
    const start = -math.pi / 2;
    final sweep = 2 * math.pi * seal;

    // Faint track so the trace reads as a ring being completed.
    canvas.drawCircle(
      c,
      r,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1
        ..color = Colors.white.withValues(alpha: 0.08 * fade),
    );

    canvas.drawArc(
      rect,
      start,
      sweep,
      false,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeWidth = 2.5 * fade + 0.5
        ..shader = SweepGradient(
          transform: const GradientRotation(start),
          colors: [
            AuraColors.mint.withValues(alpha: 0.2 * fade),
            AuraColors.mint.withValues(alpha: fade),
            AuraColors.sky.withValues(alpha: fade),
          ],
          stops: const [0, 0.5, 1],
        ).createShader(rect),
    );

    // Glowing head that leads the trace.
    if (seal < 1) {
      final a = start + sweep;
      final head = c + Offset(math.cos(a), math.sin(a)) * r;
      canvas.drawCircle(
        head,
        6,
        Paint()
          ..color = AuraColors.sky.withValues(alpha: 0.5)
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 6),
      );
      canvas.drawCircle(head, 2.5, Paint()..color = Colors.white);
    }
  }

  @override
  bool shouldRepaint(_SealPainter old) => old.seal != seal || old.sealed != sealed;
}

/// Circle bloom from the Sign in button, then a curtain whose bottom edge
/// bulges like liquid as it lifts off the top of the screen.
class _SkyClipper extends CustomClipper<Path> {
  const _SkyClipper({required this.bloom, required this.lift, required this.size});

  final double bloom;
  final double lift;
  final Size size;

  @override
  Path getClip(Size s) {
    if (lift <= 0) {
      final origin = Offset(s.width / 2, s.height * 0.88);
      final r = math.max(bloom * s.longestSide * 1.3, 0.01);
      return Path()..addOval(Rect.fromCircle(center: origin, radius: r));
    }
    final y = s.height * (1 - lift);
    final bulge = 140 * math.sin(math.pi * lift);
    return Path()
      ..moveTo(0, 0)
      ..lineTo(s.width, 0)
      ..lineTo(s.width, y)
      ..quadraticBezierTo(s.width / 2, y + bulge, 0, y)
      ..close();
  }

  @override
  bool shouldReclip(_SkyClipper old) => old.bloom != bloom || old.lift != lift || old.size != size;
}
