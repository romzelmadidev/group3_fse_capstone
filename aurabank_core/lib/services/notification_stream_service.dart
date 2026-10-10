import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import '../models/notification_model.dart';
import '../navigation/root_navigator.dart';
import '../widgets/security_dialog.dart';
import 'auth_api_service.dart' show AuthApiService, defaultBackendHost;
import 'bank_service.dart';

class SecurityAlertEvent {
  final String title;
  final String message;
  final String deviceName;
  final String deviceId;
  final String deviceType;
  final String clientIp;
  final String timestamp;
  final String targetDeviceId;
  final String notificationId;
  final String status;
  final bool isThirdDevice;
  final String replacedDeviceId;
  final String replacedDeviceName;

  SecurityAlertEvent({
    required this.title,
    required this.message,
    required this.deviceName,
    this.deviceId = '',
    this.deviceType = 'MOBILE',
    required this.clientIp,
    required this.timestamp,
    required this.targetDeviceId,
    required this.notificationId,
    this.status = 'PENDING_APPROVAL',
    this.isThirdDevice = false,
    this.replacedDeviceId = '',
    this.replacedDeviceName = '',
  });

  bool get isDesktopSession =>
      deviceType.toUpperCase() == 'WEB' ||
      title.toLowerCase().contains('desktop') ||
      message.toLowerCase().contains('desktop') ||
      deviceName.toLowerCase().contains('laptop') ||
      deviceName.toLowerCase().contains('chrome');

  factory SecurityAlertEvent.fromJson(Map<String, dynamic> json) {
    final rawType = json['device_type'] as String?;
    final titleStr = json['title'] as String? ?? 'Security Alert: New Device Login';
    final msgStr = json['message'] as String? ?? 'A new device logged into your account.';
    final devName = json['device_name'] as String? ?? 'Unknown Device';
    final isDesktop = rawType?.toUpperCase() == 'WEB' ||
        titleStr.toLowerCase().contains('desktop') ||
        msgStr.toLowerCase().contains('desktop') ||
        devName.toLowerCase().contains('laptop') ||
        devName.toLowerCase().contains('chrome');

    return SecurityAlertEvent(
      title: titleStr,
      message: msgStr,
      deviceName: devName,
      deviceId: json['device_id'] as String? ?? '',
      deviceType: isDesktop ? 'WEB' : (rawType ?? 'MOBILE'),
      clientIp: json['client_ip'] as String? ?? 'Unknown IP',
      timestamp: json['timestamp'] as String? ?? DateTime.now().toIso8601String(),
      targetDeviceId: json['target_device_id'] as String? ?? '',
      notificationId: json['notification_id'] as String? ?? '',
      status: json['status'] as String? ?? 'PENDING_APPROVAL',
      isThirdDevice: json['is_third_device'] == true ||
          (json['message']?.toString().toLowerCase().contains('3rd device') == true) ||
          (json['message']?.toString().toLowerCase().contains('3rd mobile') == true),
      replacedDeviceId: json['replaced_device_id'] as String? ?? '',
      replacedDeviceName: json['replaced_device_name'] as String? ?? '',
    );
  }
}

class NotificationStreamService extends ChangeNotifier {
  static final NotificationStreamService _instance = NotificationStreamService._internal();
  factory NotificationStreamService() => _instance;
  NotificationStreamService._internal();

  final _alertController = StreamController<SecurityAlertEvent>.broadcast();
  Stream<SecurityAlertEvent> get alertStream => _alertController.stream;

  final _deviceApprovalController = StreamController<Map<String, dynamic>>.broadcast();
  Stream<Map<String, dynamic>> get deviceApprovalStream => _deviceApprovalController.stream;

  final _notificationController = StreamController<AppNotification>.broadcast();
  Stream<AppNotification> get notificationStream => _notificationController.stream;

  final _bannerController = StreamController<AppNotification>.broadcast();
  Stream<AppNotification> get bannerStream => _bannerController.stream;

  final List<AppNotification> _notifications = [];
  List<AppNotification> get notifications => List.unmodifiable(_notifications);
  int get unreadCount => _notifications.where((n) => !n.isRead).length;

  http.Client? _streamClient;
  http.Client? httpClient;
  Timer? _reconnectTimer;
  Timer? _pollFallbackTimer;
  String? _activeUserId;
  bool _isConnected = false;
  bool _isRevoking = false;
  final Set<String> _seenNotificationIds = {};
  final Set<String> _promptedApprovalAlertIds = {};
  bool _isApprovalDialogShowing = false;

  bool enablePollingFallback = true;
  bool enableAutoReconnect = true;

  bool get isConnected => _isConnected;

  String get notificationServiceUrl => 'http://$defaultBackendHost:8083';

  http.Client get _effectiveClient => httpClient ?? http.Client();

  DateTime? _sessionConnectedAt;

  /// Connects to real-time notification stream for specified user
  void connect(String userId) {
    if (userId.isEmpty) return;
    if (_activeUserId == userId && _isConnected) return;
    disconnect();
    _isRevoking = false;
    _activeUserId = userId;
    _sessionConnectedAt = DateTime.now();
    _seedExistingHistory(userId);
    _startSseStream(userId);
    if (enablePollingFallback) {
      _startPollFallback(userId);
    }
  }

  /// Mark all historical notifications as already seen so past alerts never pop up on new login
  Future<void> _seedExistingHistory(String userId) async {
    try {
      final client = _effectiveClient;
      final url = Uri.parse('$notificationServiceUrl/api/v1/notifications/history?userId=$userId');
      final res = await client.get(url).timeout(const Duration(seconds: 3));
      if (res.statusCode == 200) {
        final items = jsonDecode(res.body) as List<dynamic>;
        for (final item in items) {
          if (item is Map<String, dynamic>) {
            final id = item['notificationId'] as String? ?? item['notification_id'] as String? ?? '';
            if (id.isNotEmpty) {
              _seenNotificationIds.add(id);
            }
          }
        }
      }
    } catch (_) {}
  }

  Future<void> _startSseStream(String userId) async {
    _streamClient = _effectiveClient;
    final url = Uri.parse('$notificationServiceUrl/api/v1/notifications/stream?userId=$userId');

    try {
      final request = http.Request('GET', url)
        ..headers['Accept'] = 'text/event-stream'
        ..headers['Cache-Control'] = 'no-cache';

      final response = await _streamClient!.send(request);
      if (response.statusCode == 200) {
        _isConnected = true;
        debugPrint('[NotificationStream] Connected to SSE stream for user $userId');

        String currentEvent = '';
        response.stream
            .transform(utf8.decoder)
            .transform(const LineSplitter())
            .listen(
          (line) {
            final trimmed = line.trim();
            if (trimmed.startsWith('event:')) {
              currentEvent = trimmed.substring(6).trim();
            } else if (trimmed.startsWith('data:')) {
              final dataStr = trimmed.substring(5).trim();
              if (dataStr.isNotEmpty) {
                try {
                  final data = jsonDecode(dataStr) as Map<String, dynamic>;
                  final type = data['type'] as String? ?? currentEvent;
                  if (type == 'SECURITY_ALERT') {
                    final alert = SecurityAlertEvent.fromJson(data);
                    _onSecurityAlertReceived(alert);
                  } else if (type == 'DEVICE_APPROVED' || type == 'DEVICE_REVOKED') {
                    _onDeviceApprovalEventReceived(data);
                  } else if (type == 'TRANSACTION_ALERT' ||
                      type == 'TRANSACTION_TOAST' ||
                      type == 'TRANSFER_ALERT') {
                    _onTransactionAlertReceived(data);
                  }
                } catch (e) {
                  debugPrint('[NotificationStream] Failed to parse alert data: $e');
                }
              }
              currentEvent = '';
            }
          },
          onError: (e) {
            debugPrint('[NotificationStream] SSE stream error: $e');
            _scheduleReconnect();
          },
          onDone: () {
            debugPrint('[NotificationStream] SSE stream closed by server.');
            _scheduleReconnect();
          },
          cancelOnError: true,
        );
      } else {
        _scheduleReconnect();
      }
    } catch (e) {
      debugPrint('[NotificationStream] Error establishing SSE connection: $e');
      _scheduleReconnect();
    }
  }

  void _scheduleReconnect() {
    _isConnected = false;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    if (enableAutoReconnect && _activeUserId != null) {
      _reconnectTimer = Timer(const Duration(seconds: 4), () {
        if (_activeUserId != null) {
          _startSseStream(_activeUserId!);
        }
      });
    }
  }

  /// Auxiliary polling fallback to guarantee notification delivery for NEW alerts only
  void _startPollFallback(String userId) {
    _pollFallbackTimer?.cancel();
    _pollFallbackTimer = null;
    if (!enablePollingFallback) return;
    _pollFallbackTimer = Timer.periodic(const Duration(seconds: 5), (_) async {
      if (_activeUserId == null || !AuthApiService().isAuthenticated) return;
      try {
        final client = _effectiveClient;
        final url = Uri.parse('$notificationServiceUrl/api/v1/notifications/history?userId=$userId');
        final res = await client.get(url).timeout(const Duration(seconds: 3));
        if (res.statusCode == 200) {
          final items = jsonDecode(res.body) as List<dynamic>;
          for (final item in items) {
            if (item is Map<String, dynamic>) {
              final type = item['type'] as String?;
              final id = item['notificationId'] as String? ?? item['notification_id'] as String? ?? '';
              final sentAtStr = item['sentAt'] as String? ?? item['createdAt'] as String?;
              final sentAt = sentAtStr != null ? DateTime.tryParse(sentAtStr) : null;

              // Only dispatch if notification occurred after current session connection
              final isAfterSession = _sessionConnectedAt == null ||
                  (sentAt != null && sentAt.isAfter(_sessionConnectedAt!.subtract(const Duration(seconds: 2))));

              if (type == 'SECURITY_ALERT' && id.isNotEmpty && isAfterSession && _seenNotificationIds.add(id)) {
                final message = item['message'] as String? ?? '';
                String extractedDevice = 'Secondary Device';
                final match = RegExp(r'\((.*?)\)').firstMatch(message);
                if (match != null && match.group(1) != null) {
                  extractedDevice = match.group(1)!;
                }
                final alert = SecurityAlertEvent(
                  title: 'Security Alert: New Device Login',
                  message: message,
                  deviceName: extractedDevice,
                  clientIp: 'Remote IP',
                  timestamp: sentAtStr ?? DateTime.now().toIso8601String(),
                  targetDeviceId: '',
                  notificationId: id,
                );
                _onSecurityAlertReceived(alert);
              } else if ((type == 'DEVICE_REVOKED' || type == 'DEVICE_APPROVED') &&
                  id.isNotEmpty &&
                  isAfterSession &&
                  _seenNotificationIds.add(id)) {
                final message = item['message'] as String? ?? '';
                String targetDevId = '';
                if (item['device_id'] != null && item['device_id'].toString().isNotEmpty) {
                  targetDevId = item['device_id'].toString().trim();
                } else if (item['deviceId'] != null && item['deviceId'].toString().isNotEmpty) {
                  targetDevId = item['deviceId'].toString().trim();
                } else {
                  final parenMatch = RegExp(r'\((.*?)\)').firstMatch(message);
                  if (parenMatch != null && parenMatch.group(1) != null) {
                    targetDevId = parenMatch.group(1)!.trim();
                  } else {
                    final parts = message.split(': ');
                    if (parts.length > 1) {
                      targetDevId = parts[1].trim();
                    }
                  }
                }
                if (targetDevId.isNotEmpty) {
                  _onDeviceApprovalEventReceived({
                    'type': type,
                    'user_id': userId,
                    'device_id': targetDevId,
                    'timestamp': sentAtStr ?? DateTime.now().toIso8601String(),
                  });
                }
              } else if ((type == 'TRANSACTION_ALERT' || type == 'TRANSFER_ALERT' || type == 'TRANSACTION_TOAST') &&
                  id.isNotEmpty &&
                  isAfterSession &&
                  _seenNotificationIds.add(id)) {
                _onTransactionAlertReceived(item);
              }
            }
          }
        }
      } catch (_) {}
    });
  }


  void postNotification(AppNotification notif, {bool showBanner = true}) {
    final existingIndex = _notifications.indexWhere((n) => n.id == notif.id);
    if (existingIndex >= 0) {
      _notifications[existingIndex] = notif;
    } else {
      _notifications.insert(0, notif);
      if (_notifications.length > 50) {
        _notifications.removeLast();
      }
    }
    _notificationController.add(notif);
    if (showBanner) {
      _bannerController.add(notif);
    }
    notifyListeners();
  }

  void markAsRead(String id) {
    final idx = _notifications.indexWhere((n) => n.id == id);
    if (idx >= 0 && !_notifications[idx].isRead) {
      _notifications[idx].isRead = true;
      notifyListeners();
    }
  }

  void markAllAsRead() {
    for (final n in _notifications) {
      n.isRead = true;
    }
    notifyListeners();
  }

  void clearAll() {
    _notifications.clear();
    notifyListeners();
  }

  void notifyTransfer({
    required double amount,
    required String recipient,
    required String reference,
    bool isIncoming = false,
    String? status,
    String? message,
  }) {
    final notif = AppNotification(
      id: 'TRX-${DateTime.now().millisecondsSinceEpoch}',
      title: isIncoming ? 'Funds Received' : 'Fund Transfer Settled',
      message: message ??
          (isIncoming
              ? 'Received ₱${amount.toStringAsFixed(2)} from $recipient. Ref: $reference'
              : 'Transferred ₱${amount.toStringAsFixed(2)} to $recipient. Ref: $reference'),
      category: NotificationCategory.transfer,
      severity: NotificationSeverity.success,
      timestamp: DateTime.now(),
      metadata: {
        'amount': amount,
        'recipient': recipient,
        'reference': reference,
        'isIncoming': isIncoming,
        'status': status ?? 'COMMITTED',
      },
    );
    postNotification(notif, showBanner: true);
  }

  void notifySessionAlert({
    required String title,
    required String message,
    required String deviceName,
    String? clientIp,
    String? deviceType,
  }) {
    final notif = AppNotification(
      id: 'SES-${DateTime.now().millisecondsSinceEpoch}',
      title: title,
      message: message,
      category: NotificationCategory.session,
      severity: NotificationSeverity.warning,
      timestamp: DateTime.now(),
      metadata: {
        'deviceName': deviceName,
        'clientIp': clientIp ?? 'Unknown IP',
        'deviceType': deviceType ?? 'WEB',
      },
    );
    postNotification(notif, showBanner: true);
  }

  void notifySecurityAlert({
    required String title,
    required String message,
    NotificationSeverity severity = NotificationSeverity.warning,
    Map<String, dynamic>? metadata,
  }) {
    final notif = AppNotification(
      id: 'SEC-${DateTime.now().millisecondsSinceEpoch}',
      title: title,
      message: message,
      category: NotificationCategory.security,
      severity: severity,
      timestamp: DateTime.now(),
      metadata: metadata ?? {},
    );
    postNotification(notif, showBanner: true);
  }

  Future<void> fetchHistory(String userId) async {
    try {
      final client = _effectiveClient;
      final url = Uri.parse('$notificationServiceUrl/api/v1/notifications/history?userId=$userId');
      final res = await client.get(url).timeout(const Duration(seconds: 4));
      if (res.statusCode == 200) {
        final items = jsonDecode(res.body) as List<dynamic>;
        for (final item in items) {
          if (item is Map<String, dynamic>) {
            final id = item['notificationId'] as String? ?? item['notification_id'] as String? ?? '';
            final type = item['type'] as String? ?? '';
            final msg = item['message'] as String? ?? '';
            final sentAtStr = item['sentAt'] as String? ?? item['createdAt'] as String?;
            final sentAt = sentAtStr != null ? DateTime.tryParse(sentAtStr) : null;

            if (id.isNotEmpty && !_notifications.any((n) => n.id == id)) {
              NotificationCategory cat = NotificationCategory.security;
              NotificationSeverity sev = NotificationSeverity.info;
              String title = 'Notification';

              if (type == 'SECURITY_ALERT') {
                final isSession = msg.toLowerCase().contains('device') || msg.toLowerCase().contains('login');
                cat = isSession ? NotificationCategory.session : NotificationCategory.security;
                sev = NotificationSeverity.warning;
                title = isSession ? 'Session Alert' : 'Security Alert';
              } else if (type == 'DEVICE_APPROVED') {
                cat = NotificationCategory.session;
                sev = NotificationSeverity.success;
                title = 'Device Authorized';
              } else if (type == 'DEVICE_REVOKED') {
                cat = NotificationCategory.session;
                sev = NotificationSeverity.danger;
                title = 'Device Revoked';
              } else if (type == 'TRANSACTION_ALERT' || type == 'TRANSFER_ALERT') {
                cat = NotificationCategory.transfer;
                sev = NotificationSeverity.success;
                title = 'Transfer Alert';
              }

              _notifications.add(
                AppNotification(
                  id: id,
                  title: title,
                  message: msg,
                  category: cat,
                  severity: sev,
                  timestamp: sentAt ?? DateTime.now(),
                  isRead: true,
                  metadata: item,
                ),
              );
            }
          }
        }
        notifyListeners();
      }
    } catch (_) {}
  }

  void _onTransactionAlertReceived(Map<String, dynamic> data) {
    final status = (data['status'] as String? ?? 'SUCCESS').toUpperCase();
    final isFailed = status == 'FAILED' || status == 'REJECTED';
    final amtStr = data['amount']?.toString() ?? '₱0.00';
    final rawMsg = data['message'] as String? ?? 'Transfer completed successfully.';
    final isIncoming = data['isIncoming'] == true ||
        (data['type'] == 'TRANSACTION_ALERT' && data['afterBalance'] != null && rawMsg.toLowerCase().contains('received'));

    final title = isFailed
        ? 'Transfer Failed'
        : (isIncoming ? 'Funds Received' : 'Fund Transfer Settled');

    final notifId = data['notification_id'] as String? ??
        data['notificationId'] as String? ??
        'TRX-NOTIF-${data['transferId'] ?? DateTime.now().millisecondsSinceEpoch}';

    final notif = AppNotification(
      id: notifId,
      title: title,
      message: '$rawMsg (Amount: $amtStr)',
      category: NotificationCategory.transfer,
      severity: isFailed ? NotificationSeverity.danger : NotificationSeverity.success,
      timestamp: DateTime.now(),
      metadata: data,
    );
    postNotification(notif, showBanner: true);

    try {
      BankService().syncWithBackend();
    } catch (_) {}
  }

  void _onSecurityAlertReceived(SecurityAlertEvent alert) {
    // Suppress alerts and popups if user is not authenticated
    if (!AuthApiService().isAuthenticated) {
      debugPrint('[NotificationStream] Suppressed security alert because client is not authenticated.');
      return;
    }

    final notifId = alert.notificationId;
    if (notifId.isEmpty || _seenNotificationIds.add(notifId)) {
      _alertController.add(alert);
      debugPrint('[NotificationStream] Dispatched SECURITY_ALERT: ${alert.deviceName}');

      final isSession = alert.isDesktopSession ||
          alert.title.toLowerCase().contains('device') ||
          alert.message.toLowerCase().contains('device') ||
          alert.title.toLowerCase().contains('login') ||
          alert.message.toLowerCase().contains('logged');

      final notif = AppNotification(
        id: notifId.isNotEmpty ? notifId : 'SEC-${DateTime.now().millisecondsSinceEpoch}',
        title: alert.title,
        message: alert.message,
        category: isSession ? NotificationCategory.session : NotificationCategory.security,
        severity: alert.isThirdDevice ? NotificationSeverity.danger : NotificationSeverity.warning,
        timestamp: DateTime.tryParse(alert.timestamp) ?? DateTime.now(),
        metadata: {
          'deviceName': alert.deviceName,
          'deviceId': alert.deviceId,
          'deviceType': alert.deviceType,
          'clientIp': alert.clientIp,
          'status': alert.status,
          'isThirdDevice': alert.isThirdDevice,
        },
      );
      postNotification(notif, showBanner: true);

      // Only prompt the approval modal if:
      // 1. This device is the primary device
      // 2. The alert is specifically pending approval for a mobile device (not standard desktop sessions)
      // 3. We haven't already presented this specific alert in this session
      // 4. An approval dialog is not currently showing on screen
      final isPendingApproval = alert.status == 'PENDING_APPROVAL' && !alert.isDesktopSession;
      if (AuthApiService().isPrimaryDevice && isPendingApproval) {
        if (notifId.isNotEmpty && _promptedApprovalAlertIds.contains(notifId)) {
          return;
        }
        if (notifId.isNotEmpty) {
          _promptedApprovalAlertIds.add(notifId);
        }
        if (!_isApprovalDialogShowing) {
          final context = rootNavigatorKey.currentContext;
          if (context != null) {
            _isApprovalDialogShowing = true;
            SecurityApprovalDialog.show(
              context,
              alert,
              onHandled: () {
                _isApprovalDialogShowing = false;
              },
            ).then((_) {
              _isApprovalDialogShowing = false;
            });
          }
        }
      }
    }
  }

  void _onDeviceApprovalEventReceived(Map<String, dynamic> data) {
    _deviceApprovalController.add(data);
    debugPrint('[NotificationStream] Dispatched ${data['type']} for device: ${data['device_id']}');

    final type = data['type'] as String? ?? '';
    final isApproved = type == 'DEVICE_APPROVED';
    final targetDevId = (data['device_id'] as String? ?? '').toLowerCase().trim();
    final currentDevId = AuthApiService().currentDeviceId.toLowerCase().trim();
    final currentDevName = AuthApiService().currentDeviceName.toLowerCase().trim();
    final isForThisDevice = targetDevId.isNotEmpty &&
        (targetDevId == currentDevId || targetDevId == currentDevName);

    final notif = AppNotification(
      id: data['notification_id'] as String? ?? 'DEV-${DateTime.now().millisecondsSinceEpoch}',
      title: isApproved ? 'Device Authorized' : 'Device Access Revoked',
      message: isApproved
          ? 'Device ${data['device_id']} was approved by your primary device.'
          : 'Device ${data['device_id']} had its access revoked.',
      category: NotificationCategory.session,
      severity: isApproved ? NotificationSeverity.success : NotificationSeverity.danger,
      timestamp: DateTime.tryParse(data['timestamp'] as String? ?? '') ?? DateTime.now(),
      metadata: data,
    );
    postNotification(notif, showBanner: isForThisDevice);

    // If this client is not logged in, ignore session revocation events completely.
    // An unauthenticated user or login screen session cannot be revoked.
    if (!AuthApiService().isAuthenticated) {
      debugPrint('[NotificationStream] Ignored $type because client is not currently authenticated.');
      return;
    }

    // Must have a non-empty target device id; empty never matches all devices
    if (targetDevId.isEmpty) {
      return;
    }

    if (isForThisDevice) {
      final context = rootNavigatorKey.currentContext;
      if (type == 'DEVICE_APPROVED') {
        AuthApiService().currentIsApproved = true;
        if (context != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('✓ Device Approved! Your primary device authorized banking transactions.'),
              backgroundColor: Color(0xFF107C41),
              duration: Duration(seconds: 4),
            ),
          );
        }
      } else if (type == 'DEVICE_REVOKED') {
        if (_isRevoking) {
          debugPrint('[NotificationStream] Ignoring duplicate DEVICE_REVOKED event.');
          return;
        }
        _isRevoking = true;
        AuthApiService().currentIsApproved = false;
        AuthApiService().logout();
        disconnect();
        if (context != null) {
          ScaffoldMessenger.of(context).clearSnackBars();
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Access revoked. You have been logged out of this session.'),
              backgroundColor: Colors.redAccent,
              duration: Duration(seconds: 4),
            ),
          );
          Navigator.of(context).pushNamedAndRemoveUntil('/login', (route) => false);
        }
      }
    }
  }

  /// Manually inject alert (useful for test cases or instant demonstration)
  void injectAlert(SecurityAlertEvent alert) {
    _onSecurityAlertReceived(alert);
  }

  /// Manually inject device approval event (useful for test cases or instant demonstration)
  void injectDeviceApprovalEvent(Map<String, dynamic> data) {
    _onDeviceApprovalEventReceived(data);
  }

  void disconnect() {
    _isConnected = false;
    _isRevoking = false;
    _activeUserId = null;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _pollFallbackTimer?.cancel();
    _pollFallbackTimer = null;
    _streamClient?.close();
    _streamClient = null;
    _promptedApprovalAlertIds.clear();
    _isApprovalDialogShowing = false;
  }
}

