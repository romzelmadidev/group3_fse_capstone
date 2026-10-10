import 'package:flutter/material.dart';

import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_core/services/notification_stream_service.dart';
import 'package:aurabank_core/theme/aura_theme.dart';
import 'package:aurabank_core/widgets/notification_center_modal.dart';

class WebHeader extends StatelessWidget {
  final VoidCallback? onQuickTransfer;
  final VoidCallback? onRefresh;

  const WebHeader({
    super.key,
    this.onQuickTransfer,
    this.onRefresh,
  });

  @override
  Widget build(BuildContext context) {
    final name = BankService().user.name;
    final first = name.isNotEmpty ? name.split(' ').first : 'User';
    final h = DateTime.now().hour;
    final greeting = h < 12 ? 'Good morning' : (h < 18 ? 'Good afternoon' : 'Good evening');

    return Container(
      height: 76,
      padding: const EdgeInsets.symmetric(horizontal: 32),
      color: AuraColors.canvas,
      child: Row(
        children: [
          Expanded(
            child: Text(
              '$greeting, $first',
              style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600, letterSpacing: -0.5),
            ),
          ),
          IconButton(
            tooltip: 'Refresh',
            onPressed: onRefresh,
            icon: const Icon(Icons.refresh_rounded, color: AuraColors.textSecondary),
          ),
          const SizedBox(width: 4),
          ListenableBuilder(
            listenable: NotificationStreamService(),
            builder: (context, _) {
              final unread = NotificationStreamService().unreadCount;
              return Stack(
                clipBehavior: Clip.none,
                children: [
                  IconButton(
                    tooltip: 'Notifications',
                    onPressed: () => NotificationCenterModal.show(context),
                    icon: const Icon(Icons.notifications_none_rounded, color: AuraColors.textSecondary),
                  ),
                  if (unread > 0)
                    Positioned(
                      top: 6,
                      right: 6,
                      child: Container(
                        padding: const EdgeInsets.all(4),
                        decoration: const BoxDecoration(
                          color: AuraColors.debitRed,
                          shape: BoxShape.circle,
                        ),
                        constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
                        child: Text(
                          unread > 9 ? '9+' : '$unread',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 9,
                            fontWeight: FontWeight.bold,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ),
                    ),
                ],
              );
            },
          ),
          const SizedBox(width: 12),
          FilledButton.icon(
            onPressed: onQuickTransfer,
            icon: const Icon(Icons.north_east_rounded, size: 18),
            label: const Text('Send money'),
            style: FilledButton.styleFrom(minimumSize: const Size(0, 44), padding: const EdgeInsets.symmetric(horizontal: 20)),
          ),
        ],
      ),
    );
  }
}
