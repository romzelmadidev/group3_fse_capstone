import 'package:flutter/material.dart';
import '../models/notification_model.dart';
import '../services/notification_stream_service.dart';
import '../theme/aura_theme.dart';

class NotificationCenterModal extends StatefulWidget {
  final bool showTestSimulator;
  const NotificationCenterModal({super.key, this.showTestSimulator = false});

  static Future<void> show(BuildContext context) {
    final isDesktop = MediaQuery.of(context).size.width >= 768;

    if (isDesktop) {
      return showDialog(
        context: context,
        barrierColor: Colors.black54,
        builder: (ctx) => Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(
              maxWidth: 580,
              maxHeight: 720,
            ),
            child: Dialog(
              backgroundColor: AuraColors.canvas,
              shape: const RoundedRectangleBorder(
                borderRadius: BorderRadius.all(Radius.circular(24)),
                side: BorderSide(color: AuraColors.cardBorder, width: 1),
              ),
              child: const ClipRRect(
                borderRadius: BorderRadius.all(Radius.circular(24)),
                child: NotificationCenterModal(),
              ),
            ),
          ),
        ),
      );
    }

    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: AuraColors.canvas,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) => FractionallySizedBox(
        heightFactor: 0.85,
        child: const NotificationCenterModal(),
      ),
    );
  }

  @override
  State<NotificationCenterModal> createState() => _NotificationCenterModalState();
}

class _NotificationCenterModalState extends State<NotificationCenterModal> {
  NotificationCategory? _selectedCategory;
  bool _showTestSection = false;

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
    return ListenableBuilder(
      listenable: NotificationStreamService(),
      builder: (context, _) {
        final service = NotificationStreamService();
        final allNotifs = service.notifications;
        final filteredNotifs = _selectedCategory == null
            ? allNotifs
            : allNotifs.where((n) => n.category == _selectedCategory).toList();

        final unreadCount = service.unreadCount;

        return Scaffold(
          backgroundColor: Colors.transparent,
          body: Column(
            children: [
              _buildHeader(context, service, unreadCount),
              _buildFilterTabs(allNotifs),
              const Divider(color: AuraColors.cardBorder, height: 1),
              Expanded(
                child: filteredNotifs.isEmpty
                    ? _buildEmptyState()
                    : ListView.separated(
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                        itemCount: filteredNotifs.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 12),
                        itemBuilder: (ctx, index) {
                          final item = filteredNotifs[index];
                          return _buildNotificationCard(service, item);
                        },
                      ),
              ),
              if (widget.showTestSimulator) _buildTestAlertControls(service),
            ],
          ),
        );
      },
    );
  }

  Widget _buildHeader(
    BuildContext context,
    NotificationStreamService service,
    int unreadCount,
  ) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AuraColors.cardBorder, width: 1)),
      ),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: AuraColors.bgLavender,
              shape: BoxShape.circle,
              border: Border.all(color: AuraColors.borderLavender, width: 1),
            ),
            child: const Icon(
              Icons.notifications_active_rounded,
              color: AuraColors.accentVibrant,
              size: 17,
            ),
          ),
          const SizedBox(width: 8),
          const Flexible(
            child: Text(
              'Notifications',
              style: TextStyle(
                color: AuraColors.ink,
                fontSize: 17,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.3,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (unreadCount > 0) ...[
            const SizedBox(width: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: AuraColors.debitRed,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                '$unreadCount',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
          const Spacer(),
          if (service.notifications.isNotEmpty && unreadCount > 0)
            IconButton(
              tooltip: 'Mark all read',
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.done_all_rounded, size: 19),
              color: AuraColors.textSecondary,
              onPressed: () => service.markAllAsRead(),
            ),
          if (service.notifications.isNotEmpty)
            IconButton(
              tooltip: 'Clear history',
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.delete_outline_rounded, size: 19),
              color: AuraColors.textSecondary,
              onPressed: () => service.clearAll(),
            ),
          IconButton(
            tooltip: 'Close',
            visualDensity: VisualDensity.compact,
            icon: const Icon(Icons.close_rounded, size: 20),
            color: AuraColors.textSecondary,
            onPressed: () => Navigator.of(context).pop(),
          ),
        ],
      ),
    );
  }

  Widget _buildFilterTabs(List<AppNotification> allNotifs) {
    final transferCount = allNotifs.where((n) => n.category == NotificationCategory.transfer).length;
    final sessionCount = allNotifs.where((n) => n.category == NotificationCategory.session).length;
    final securityCount = allNotifs.where((n) => n.category == NotificationCategory.security).length;

    final categories = <(NotificationCategory?, String, int)>[
      (null, 'All', allNotifs.length),
      (NotificationCategory.transfer, 'Transfers', transferCount),
      (NotificationCategory.session, 'Sessions', sessionCount),
      (NotificationCategory.security, 'Security', securityCount),
    ];

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: categories.map((tab) {
          final isSelected = _selectedCategory == tab.$1;
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Text(
                '${tab.$2} (${tab.$3})',
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: isSelected ? FontWeight.w600 : FontWeight.w500,
                  color: isSelected ? Colors.white : AuraColors.textSecondary,
                ),
              ),
              selected: isSelected,
              selectedColor: AuraColors.ink,
              backgroundColor: AuraColors.surface,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
                side: BorderSide(
                  color: isSelected ? AuraColors.ink : AuraColors.cardBorder,
                ),
              ),
              showCheckmark: false,
              onSelected: (_) {
                setState(() => _selectedCategory = tab.$1);
              },
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: AuraColors.surface,
                shape: BoxShape.circle,
                border: Border.all(color: AuraColors.cardBorder),
              ),
              child: const Icon(
                Icons.notifications_off_outlined,
                color: AuraColors.textSecondary,
                size: 30,
              ),
            ),
            const SizedBox(height: 16),
            const Text(
              'All Caught Up',
              style: TextStyle(
                color: AuraColors.ink,
                fontSize: 17,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 6),
            const Text(
              'No alerts in this category. You will be notified in real time when account events occur.',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: AuraColors.textSecondary,
                fontSize: 13,
                height: 1.4,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNotificationCard(
    NotificationStreamService service,
    AppNotification item,
  ) {
    final cat = item.category;
    final (pillBg, pillText) = _getSemanticColors(cat, item.severity);

    return InkWell(
      onTap: () => service.markAsRead(item.id),
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: item.isRead ? AuraColors.canvas : AuraColors.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: AuraColors.cardBorder,
            width: 1,
          ),
          boxShadow: item.isRead ? null : AuraColors.cardShadow,
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
              child: Icon(cat.icon, color: pillText, size: 19),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
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
                            letterSpacing: 0.4,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        item.timeAgo,
                        style: const TextStyle(
                          color: AuraColors.textMuted,
                          fontSize: 11,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const Spacer(),
                      if (!item.isRead)
                        Container(
                          width: 8,
                          height: 8,
                          decoration: const BoxDecoration(
                            color: AuraColors.accentVibrant,
                            shape: BoxShape.circle,
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    item.title,
                    style: TextStyle(
                      color: AuraColors.ink,
                      fontSize: 14.5,
                      fontWeight: item.isRead ? FontWeight.w500 : FontWeight.w700,
                      letterSpacing: -0.2,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    item.message,
                    style: const TextStyle(
                      color: AuraColors.textSecondary,
                      fontSize: 12.5,
                      height: 1.35,
                    ),
                  ),
                  if (item.metadata.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    _buildMetadataChips(item),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMetadataChips(AppNotification item) {
    final meta = item.metadata;
    final chips = <Widget>[];

    if (meta['amount'] != null) {
      chips.add(_chip('₱${meta['amount']}', AuraColors.creditGreenBg, AuraColors.creditGreen));
    }
    if (meta['reference'] != null) {
      chips.add(_chip('Ref: ${meta['reference']}', AuraColors.canvas, AuraColors.textSecondary));
    }
    if (meta['deviceName'] != null) {
      chips.add(_chip('${meta['deviceName']}', AuraColors.bgLavender, AuraColors.accentVibrant));
    }
    if (meta['clientIp'] != null) {
      chips.add(_chip('IP: ${meta['clientIp']}', AuraColors.canvas, AuraColors.textMuted));
    }

    if (chips.isEmpty) return const SizedBox.shrink();

    return Wrap(
      spacing: 6,
      runSpacing: 4,
      children: chips,
    );
  }

  Widget _chip(String text, Color bg, Color textCol) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: textCol.withValues(alpha: 0.25), width: 0.8),
      ),
      child: Text(
        text,
        style: TextStyle(
          color: textCol,
          fontSize: 10.5,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  Widget _buildTestAlertControls(NotificationStreamService service) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: const BoxDecoration(
        color: AuraColors.surface,
        border: Border(top: BorderSide(color: AuraColors.cardBorder)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              const Icon(Icons.tune_rounded, size: 16, color: AuraColors.textSecondary),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  'Live Push Simulator',
                  style: TextStyle(
                    color: AuraColors.textSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              TextButton(
                onPressed: () {
                  setState(() => _showTestSection = !_showTestSection);
                },
                style: TextButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  foregroundColor: AuraColors.accentVibrant,
                ),
                child: Text(
                  _showTestSection ? 'Hide' : 'Show',
                  style: const TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
          if (_showTestSection) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.laptop_chromebook_rounded, size: 14),
                    label: const Text('Session', style: TextStyle(fontSize: 11)),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      side: const BorderSide(color: AuraColors.sky),
                      foregroundColor: AuraColors.sky,
                    ),
                    onPressed: () {
                      service.notifySessionAlert(
                        title: 'New Desktop Session Login',
                        message: 'Chrome on Windows signed in to your account from 192.168.1.154.',
                        deviceName: 'Chrome on Windows',
                        clientIp: '192.168.1.154',
                        deviceType: 'WEB',
                      );
                    },
                  ),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.arrow_downward_rounded, size: 14),
                    label: const Text('Transfer', style: TextStyle(fontSize: 11)),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      side: const BorderSide(color: AuraColors.accent),
                      foregroundColor: AuraColors.accent,
                    ),
                    onPressed: () {
                      service.notifyTransfer(
                        amount: 3500.0,
                        recipient: 'Maria Clara Santos',
                        reference: 'FT261010-849201',
                        isIncoming: true,
                        message: 'Received ₱3,500.00 from Maria Clara Santos via InstaPay.',
                      );
                    },
                  ),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.security_rounded, size: 14),
                    label: const Text('Security', style: TextStyle(fontSize: 11)),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      side: const BorderSide(color: AuraColors.debitRed),
                      foregroundColor: AuraColors.debitRed,
                    ),
                    onPressed: () {
                      service.notifySecurityAlert(
                        title: 'Device Security Warning',
                        message: 'Screen recording or mirroring software was detected during banking.',
                        severity: NotificationSeverity.warning,
                        metadata: {'threat': 'SCREEN_RECORDING_DETECTED'},
                      );
                    },
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
