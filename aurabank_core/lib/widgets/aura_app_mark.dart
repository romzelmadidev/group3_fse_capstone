import 'package:flutter/material.dart';

/// The Aura Bank app mark: a violet rounded square, a white A,
/// and a slash through the letter.
class AuraAppMark extends StatelessWidget {
  final double size;
  final double borderRadius;

  const AuraAppMark({
    super.key,
    this.size = 42,
    this.borderRadius = 12,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(
        painter: _AuraAppMarkPainter(borderRadius: borderRadius),
      ),
    );
  }
}

class _AuraAppMarkPainter extends CustomPainter {
  final double borderRadius;

  _AuraAppMarkPainter({required this.borderRadius});

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final radius = borderRadius.clamp(0.0, size.shortestSide / 2);
    final rrect = RRect.fromRectAndRadius(rect, Radius.circular(radius));

    canvas.drawRRect(
      rrect,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF2F0174), Color(0xFF470DA5)],
        ).createShader(rect),
    );

    canvas.save();
    canvas.clipRRect(rrect);

    final letter = TextPainter(
      text: TextSpan(
        text: 'A',
        style: TextStyle(
          color: Colors.white,
          fontSize: size.width * 0.58,
          fontWeight: FontWeight.w800,
          fontFamily: 'Inter',
          height: 1,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();

    letter.paint(
      canvas,
      Offset(
        (size.width - letter.width) / 2,
        (size.height - letter.height) / 2 - size.height * 0.03,
      ),
    );

    final slash = Paint()
      ..color = Colors.white
      ..strokeWidth = size.width * 0.055
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(
      Offset(size.width * 0.20, size.height * 0.34),
      Offset(size.width * 0.80, size.height * 0.70),
      slash,
    );

    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _AuraAppMarkPainter oldDelegate) =>
      oldDelegate.borderRadius != borderRadius;
}
