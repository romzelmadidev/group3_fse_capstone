import 'dart:async';
import 'package:flutter/material.dart';
import '../models/notification_model.dart';
import '../services/bank_service.dart';
import '../theme/aura_theme.dart';

class InAppNotificationBanner {
  static OverlayEntry? _currentEntry;
  static Timer? _dismissTimer;

  static void show(
    BuildContext context,
    AppNotification notification, {
    VoidCallback? onTap,
    Duration duration = const Duration(seconds: 4),
  }) {
    // Respect user push alert preferences
    if (!BankService().user.pushAlertsEnabled) {
      return;
    }

    _dismissTimer?.cancel();
    _currentEntry?.remove();
    _currentEntry = null;

    final overlay = Overlay.of(context, rootOverlay: true);

    late final OverlayEntry entry;
    entry = OverlayEntry(
      builder: (ctx) => _BannerOverlayWidget(
        notification: notification,
        onDismiss: () {
          _dismiss();
        },
        onTap: () {
          _dismiss();
          onTap?.call();
        },
      ),
    );

    _currentEntry = entry;
    overlay.insert(entry);

    _dismissTimer = Timer(duration, () {
      _dismiss();
    });
  }

  static void _dismiss() {
    _dismissTimer?.cancel();
    _dismissTimer = null;
    _currentEntry?.remove();
    _currentEntry = null;
  }
}

class _BannerOverlayWidget extends StatefulWidget {
  final AppNotification notification;
  final VoidCallback onDismiss;
  final VoidCallback onTap;

  const _BannerOverlayWidget({
    required this.notification,
    required this.onDismiss,
    required this.onTap,
  });

  @override
  State<_BannerOverlayWidget> createState() => _BannerOverlayWidgetState();
}

class _BannerOverlayWidgetState extends State<_BannerOverlayWidget>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<Offset> _slideAnimation;
  late final Animation<double> _fadeAnimation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 280),
    );

    _slideAnimation = Tween<Offset>(
      begin: const Offset(0, -0.35),
      end: Offset.zero,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: AuraMotion.emphasized,
    ));

    _fadeAnimation = CurvedAnimation(
      parent: _controller,
      curve: Curves.easeOut,
    );

    _controller.forward();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _handleDismiss() async {
    await _controller.reverse();
    widget.onDismiss();
  }

  (Color, Color) _getSemanticColors(NotificationCategory cat, NotificationSeverity sev) {
    if (sev == NotificationSeverity.danger) {
      return (AuraColors.debitRedBg, AuraColors.debitRed);
    }
    switch (cat) {
      case NotificationCategory.transfer:
        return (AuraColors.creditGreenBg, AuraColors.creditGreen);
      case NotificationCategory.session:
        return (AuraColors.bgLavender, AuraColors.accentVibrant);
      case NotificationCategory.security:
        if (sev == NotificationSeverity.warning) {
          return (const Color(0xFFFEF3C7), AuraColors.amberWarning);
        }
        return (AuraColors.tintPurple, AuraColors.accent);
    }
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final topPadding = media.padding.top;
    final cat = widget.notification.category;
    final sev = widget.notification.severity;
    final (pillBg, pillText) = _getSemanticColors(cat, sev);

    return Positioned(
      top: topPadding + 10,
      left: 16,
      right: 16,
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: SlideTransition(
            position: _slideAnimation,
            child: FadeTransition(
              opacity: _fadeAnimation,
              child: GestureDetector(
                onVerticalDragUpdate: (details) {
                  if (details.primaryDelta != null && details.primaryDelta! < -6) {
                    _handleDismiss();
                  }
                },
                child: Material(
                  color: Colors.transparent,
                  child: InkWell(
                    onTap: widget.onTap,
                    borderRadius: BorderRadius.circular(16),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
                      decoration: BoxDecoration(
                        color: AuraColors.surface,
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: AuraColors.cardBorder,
                          width: 1,
                        ),
                        boxShadow: AuraColors.cardShadow,
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Container(
                            width: 38,
                            height: 38,
                            decoration: BoxDecoration(
                              color: pillBg,
                              shape: BoxShape.circle,
                              border: Border.all(
                                color: pillText.withValues(alpha: 0.25),
                                width: 1,
                              ),
                            ),
                            child: Icon(
                              cat.icon,
                              color: pillText,
                              size: 19,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Container(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 6,
                                        vertical: 2,
                                      ),
                                      decoration: BoxDecoration(
                                        color: pillBg,
                                        borderRadius: BorderRadius.circular(5),
                                      ),
                                      child: Text(
                                        cat.label.toUpperCase(),
                                        style: TextStyle(
                                          color: pillText,
                                          fontSize: 9.5,
                                          fontWeight: FontWeight.w700,
                                          letterSpacing: 0.5,
                                        ),
                                      ),
                                    ),
                                    const Spacer(),
                                    Text(
                                      widget.notification.timeAgo,
                                      style: const TextStyle(
                                        color: AuraColors.textMuted,
                                        fontSize: 11,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  widget.notification.title,
                                  style: const TextStyle(
                                    color: AuraColors.ink,
                                    fontSize: 13.5,
                                    fontWeight: FontWeight.w700,
                                    letterSpacing: -0.2,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  widget.notification.message,
                                  style: const TextStyle(
                                    color: AuraColors.textSecondary,
                                    fontSize: 12.5,
                                    height: 1.35,
                                  ),
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(width: 8),
                          IconButton(
                            padding: EdgeInsets.zero,
                            constraints: const BoxConstraints(),
                            icon: const Icon(
                              Icons.close_rounded,
                              size: 18,
                              color: AuraColors.textMuted,
                            ),
                            onPressed: _handleDismiss,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
