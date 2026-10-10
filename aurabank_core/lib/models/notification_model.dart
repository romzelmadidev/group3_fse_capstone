import 'package:flutter/material.dart';

enum NotificationCategory {
  session,
  transfer,
  security;

  String get label {
    switch (this) {
      case NotificationCategory.session:
        return 'Sessions';
      case NotificationCategory.transfer:
        return 'Transfers';
      case NotificationCategory.security:
        return 'Security';
    }
  }

  IconData get icon {
    switch (this) {
      case NotificationCategory.session:
        return Icons.devices_rounded;
      case NotificationCategory.transfer:
        return Icons.swap_horiz_rounded;
      case NotificationCategory.security:
        return Icons.shield_rounded;
    }
  }
}

enum NotificationSeverity {
  info,
  success,
  warning,
  danger;

  Color get color {
    switch (this) {
      case NotificationSeverity.info:
        return const Color(0xFF0284C7); // Sky
      case NotificationSeverity.success:
        return const Color(0xFF107C41); // Emerald / Forest
      case NotificationSeverity.warning:
        return const Color(0xFFD97706); // Amber
      case NotificationSeverity.danger:
        return const Color(0xFFDC2626); // Crimson
    }
  }
}

class AppNotification {
  final String id;
  final String title;
  final String message;
  final NotificationCategory category;
  final NotificationSeverity severity;
  final DateTime timestamp;
  bool isRead;
  final Map<String, dynamic> metadata;

  AppNotification({
    required this.id,
    required this.title,
    required this.message,
    required this.category,
    this.severity = NotificationSeverity.info,
    required this.timestamp,
    this.isRead = false,
    this.metadata = const {},
  });

  String get timeAgo {
    final now = DateTime.now();
    final diff = now.difference(timestamp);

    if (diff.inSeconds < 45) {
      return 'Just now';
    } else if (diff.inMinutes < 60) {
      return '${diff.inMinutes}m ago';
    } else if (diff.inHours < 24) {
      return '${diff.inHours}h ago';
    } else if (diff.inDays == 1) {
      return 'Yesterday';
    } else if (diff.inDays < 7) {
      return '${diff.inDays}d ago';
    } else {
      return '${timestamp.month}/${timestamp.day}/${timestamp.year}';
    }
  }

  AppNotification copyWith({
    String? id,
    String? title,
    String? message,
    NotificationCategory? category,
    NotificationSeverity? severity,
    DateTime? timestamp,
    bool? isRead,
    Map<String, dynamic>? metadata,
  }) {
    return AppNotification(
      id: id ?? this.id,
      title: title ?? this.title,
      message: message ?? this.message,
      category: category ?? this.category,
      severity: severity ?? this.severity,
      timestamp: timestamp ?? this.timestamp,
      isRead: isRead ?? this.isRead,
      metadata: metadata ?? this.metadata,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'message': message,
      'category': category.name,
      'severity': severity.name,
      'timestamp': timestamp.toIso8601String(),
      'isRead': isRead,
      'metadata': metadata,
    };
  }

  factory AppNotification.fromJson(Map<String, dynamic> json) {
    NotificationCategory cat = NotificationCategory.security;
    final catStr = (json['category'] as String?)?.toLowerCase();
    if (catStr == 'session' || catStr == 'sessions') {
      cat = NotificationCategory.session;
    } else if (catStr == 'transfer' || catStr == 'transfers' || catStr == 'transaction') {
      cat = NotificationCategory.transfer;
    }

    NotificationSeverity sev = NotificationSeverity.info;
    final sevStr = (json['severity'] as String?)?.toLowerCase();
    if (sevStr == 'success') {
      sev = NotificationSeverity.success;
    } else if (sevStr == 'warning') {
      sev = NotificationSeverity.warning;
    } else if (sevStr == 'danger' || sevStr == 'error') {
      sev = NotificationSeverity.danger;
    }

    DateTime ts = DateTime.now();
    if (json['timestamp'] != null) {
      final parsed = DateTime.tryParse(json['timestamp'].toString());
      if (parsed != null) ts = parsed;
    }

    return AppNotification(
      id: json['id'] as String? ?? 'NOTIF-${DateTime.now().millisecondsSinceEpoch}',
      title: json['title'] as String? ?? 'Notification',
      message: json['message'] as String? ?? '',
      category: cat,
      severity: sev,
      timestamp: ts,
      isRead: json['isRead'] == true,
      metadata: json['metadata'] is Map<String, dynamic>
          ? json['metadata'] as Map<String, dynamic>
          : {},
    );
  }
}
