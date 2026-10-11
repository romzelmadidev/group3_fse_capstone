import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../../models/user_persona.dart';
import '../../services/auth_api_service.dart';
import '../../services/device_storage.dart';
import '../../services/notification_stream_service.dart';
import '../../theme/aura_theme.dart';
import '../auth/login_screen.dart';

class DevicesSessionsScreen extends StatefulWidget {
  final List<Map<String, dynamic>>? initialTrustedDevices;
  final List<Map<String, dynamic>>? initialWebSessions;

  const DevicesSessionsScreen({
    super.key,
    this.initialTrustedDevices,
    this.initialWebSessions,
  });

  @override
  State<DevicesSessionsScreen> createState() => _DevicesSessionsScreenState();
}

class _DevicesSessionsScreenState extends State<DevicesSessionsScreen> {
  static const Color brandViolet = AuraColors.primary;
  static const Color textDark = AuraColors.textPrimary;
  static const Color textGray = AuraColors.textMuted;
  static const Color bgLavender = Color(0xFFF5F9FB);
  static const Color borderLavender = Color(0xFFEFF6FB);

  StreamSubscription? _alertSub;
  StreamSubscription? _approvalSub;

  late final List<Map<String, dynamic>> _trustedDevices =
      widget.initialTrustedDevices != null
          ? List<Map<String, dynamic>>.from(widget.initialTrustedDevices!)
          : [];

  late final List<Map<String, dynamic>> _webSessions =
      widget.initialWebSessions != null
          ? List<Map<String, dynamic>>.from(widget.initialWebSessions!)
          : [];

  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    if (widget.initialTrustedDevices != null || widget.initialWebSessions != null) {
      _isLoading = false;
    } else {
      _loadBackendDevices();
    }
    _alertSub = NotificationStreamService().alertStream.listen((_) {
      if (mounted) _loadBackendDevices();
    });
    _approvalSub = NotificationStreamService().deviceApprovalStream.listen((_) {
      if (mounted) _loadBackendDevices();
    });
  }

  @override
  void dispose() {
    _alertSub?.cancel();
    _approvalSub?.cancel();
    super.dispose();
  }

  Future<void> _loadBackendDevices() async {
    if (widget.initialTrustedDevices != null || widget.initialWebSessions != null) {
      return;
    }
    try {
      final devices = await AuthApiService().getRegisteredDevices();
      if (!mounted) return;

      final currentId = AuthApiService().currentDeviceId.isNotEmpty
          ? AuthApiService().currentDeviceId
          : DeviceIdentity().id;
      final currentType = AuthApiService().currentDeviceType.isNotEmpty
          ? AuthApiService().currentDeviceType
          : DeviceIdentity().type;
      final currentName = AuthApiService().currentDeviceName.isNotEmpty
          ? AuthApiService().currentDeviceName
          : DeviceIdentity().name;
      final isRunningOnWeb = kIsWeb || currentType.toUpperCase() == 'WEB';
      final hasUserAuth = AuthApiService().currentUserId != null ||
          DeviceStorage.getUserId() != null ||
          AuthApiService().currentAccessToken != null;

      setState(() {
        _trustedDevices.clear();
        _webSessions.clear();

        for (final d in devices) {
          final type = (d['device_type'] as String? ?? 'MOBILE').toUpperCase();
          final isWeb = type == 'WEB';
          if (isWeb) {
            final clientIp = d['client_ip'];
            final isAppr = d['is_approved'] == true;
            final status = (d['status'] as String? ?? (isAppr ? 'APPROVED' : 'PENDING_APPROVAL')).toUpperCase();
            final isPending = !isAppr || status == 'PENDING_APPROVAL';
            String details = isPending ? 'Web Session (Pending Approval)' : 'Active Web Session';
            if (clientIp != null && clientIp.toString().isNotEmpty) {
              details = isPending ? 'IP: $clientIp • Pending Approval' : 'IP: $clientIp • Active now';
            }
            _webSessions.add({
              'id': d['device_id'] ?? '',
              'browser': d['device_name'] ?? 'Web Browser',
              'details': details,
              'location': clientIp != null && clientIp.toString().isNotEmpty ? 'IP: $clientIp' : 'Web Session',
              'isApproved': !isPending,
              'status': isPending ? 'Pending Approval' : 'Active Now',
              'icon': Icons.laptop_mac_rounded,
            });
          } else {
            final isPrim = d['is_primary'] == true;
            final isAppr = d['is_approved'] == true;
            _trustedDevices.add({
              'id': d['device_id'] ?? '',
              'name': d['device_name'] ?? 'Mobile Device',
              'os': isPrim
                  ? 'Primary Trusted Device'
                  : (isAppr ? 'Secondary Device (Approved)' : 'Secondary Device (Pending Approval)'),
              'location': d['client_ip'] != null ? 'IP: ${d['client_ip']}' : 'Registered Device',
              'isPrimary': isPrim,
              'isApproved': isAppr,
              'status': isPrim ? 'Active Now' : (isAppr ? 'Approved' : 'Pending Approval'),
              'isActive': isAppr,
              'icon': Icons.phone_iphone_rounded,
            });
          }
        }

        // Prioritize pending sessions so they appear at top of web sessions for immediate approval
        _webSessions.sort((a, b) {
          if (a['isApproved'] == false && b['isApproved'] != false) return -1;
          if (a['isApproved'] != false && b['isApproved'] == false) return 1;
          return 0;
        });

        // If running on web, ensure current web session is present and marked
        if (isRunningOnWeb && !_webSessions.any((w) => w['id'] == currentId)) {
          _webSessions.insert(0, {
            'id': currentId,
            'browser': currentName,
            'details': 'Active Web Session • Connected',
            'location': 'Current Device',
            'isApproved': true,
            'status': 'Active Now',
            'icon': Icons.laptop_mac_rounded,
          });
        }

        // If running on mobile and no devices in backend yet, register current mobile device if authenticated
        if (!isRunningOnWeb && _trustedDevices.isEmpty && hasUserAuth) {
          final isPrimary = AuthApiService().currentIsPrimaryDevice ??
              DeviceStorage.getIsPrimaryDevice() ??
              false;
          _trustedDevices.add({
            'id': currentId,
            'name': currentName,
            'os': isPrimary ? 'Primary Trusted Device' : 'Secondary Device (Approved)',
            'location': 'Current Device',
            'isPrimary': isPrimary,
            'isApproved': AuthApiService().isDeviceApproved,
            'status': 'Active Now',
            'isActive': true,
            'icon': Icons.phone_iphone_rounded,
          });
        }
      });
    } catch (e) {
      debugPrint('[DevicesSessionsScreen] Error loading devices: $e');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  bool get _isCallerPrimary {
    final currentType = AuthApiService().currentDeviceType.isNotEmpty
        ? AuthApiService().currentDeviceType
        : DeviceIdentity().type;
    final isWeb = kIsWeb || currentType.toUpperCase() == 'WEB';
    if (isWeb) return false;

    // Must be primary according to AuthApiService
    if (!AuthApiService().isPrimaryDevice) return false;
    if (AuthApiService().currentIsPrimaryDevice == false) return false;
    if (DeviceStorage.getIsPrimaryDevice() == false) return false;

    final currentId = AuthApiService().currentDeviceId.isNotEmpty
        ? AuthApiService().currentDeviceId
        : DeviceIdentity().id;

    if (_trustedDevices.isNotEmpty) {
      final matchingDev = _trustedDevices.where((d) => d['id'] == currentId).firstOrNull;
      if (matchingDev != null) {
        return matchingDev['isPrimary'] == true;
      }
      // If another device is explicitly primary in the list, this caller is definitely NOT primary
      final hasPrimary = _trustedDevices.any((d) => d['isPrimary'] == true);
      if (hasPrimary) return false;
    }

    if (AuthApiService().currentIsPrimaryDevice == true ||
        DeviceStorage.getIsPrimaryDevice() == true) {
      return true;
    }

    // STRICT: Secondary or unconfirmed devices must NEVER be treated as primary
    return false;
  }

  void _revokeDevice(int index) {
    final dev = _trustedDevices[index];
    final isPrimary = dev['isPrimary'] == true;

    if (isPrimary && !_isCallerPrimary) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Secondary and desktop devices cannot revoke the primary device.'),
          backgroundColor: Color(0xFFC8423B),
        ),
      );
      return;
    }

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
        contentPadding: const EdgeInsets.fromLTRB(24, 20, 24, 20),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 52,
              height: 52,
              decoration: const BoxDecoration(
                color: Color(0xFFE6F6EF),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.phonelink_erase_rounded, color: brandViolet, size: 26),
            ),
            const SizedBox(height: 16),
            Text(
              isPrimary ? 'Revoke Primary Device?' : 'Revoke ${dev['name']}?',
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: textDark),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 10),
            Text(
              isPrimary
                  ? 'This primary device will be unlinked. Any authorization from ${dev['name']} will be blocked until verified again with OTP.'
                  : 'This secondary device will be unlinked. Any authorization from this device will be blocked until verified again with OTP.',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12.5, color: textGray, height: 1.4),
            ),
            const SizedBox(height: 20),
            Row(
              children: [
                Expanded(
                  child: TextButton(
                    style: TextButton.styleFrom(
                      backgroundColor: const Color(0xFFF1F3F4),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    onPressed: () => Navigator.of(ctx).pop(),
                    child: const Text('Cancel', style: TextStyle(color: textDark, fontWeight: FontWeight.w700)),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: brandViolet,
                      foregroundColor: Colors.white,
                      elevation: 0,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    onPressed: () async {
                      Navigator.of(ctx).pop();
                      final deviceId = dev['id'] as String?;
                      if (deviceId != null && deviceId.isNotEmpty) {
                        await AuthApiService().revokeDevice(deviceId: deviceId);
                      }
                      setState(() {
                        _trustedDevices.removeAt(index);
                        // If primary was revoked and another device remains, promote it to primary
                        if (isPrimary && _trustedDevices.isNotEmpty) {
                          _trustedDevices[0]['isPrimary'] = true;
                          _trustedDevices[0]['status'] = 'Active Now';
                          _trustedDevices[0]['isActive'] = true;
                        }
                      });
                      if (!mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(
                          content: Text(isPrimary
                              ? '${dev['name']} primary device access revoked.'
                              : '${dev['name']} device access revoked.'),
                          backgroundColor: brandViolet,
                        ),
                      );
                      _loadBackendDevices();
                    },
                    child: Text(
                      isPrimary ? 'Revoke Primary' : 'Revoke Device',
                      style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _endWebSession(int index) async {
    final session = _webSessions[index];
    final deviceId = session['id'] as String?;
    final currentId = AuthApiService().currentDeviceId.isNotEmpty
        ? AuthApiService().currentDeviceId
        : DeviceIdentity().id;
    final isCurrentSession = (deviceId == currentId);

    if (deviceId != null && deviceId.isNotEmpty) {
      await AuthApiService().revokeDevice(deviceId: deviceId);
    }

    if (isCurrentSession) {
      NotificationStreamService().disconnect();
      if (deviceId != null && deviceId.isNotEmpty) {
        await AuthApiService().revokeDevice(deviceId: deviceId);
      }
      await AuthApiService().logout();
      if (!mounted) return;
      ScaffoldMessenger.of(context).clearSnackBars();
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const LoginScreen()),
        (route) => false,
      );
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Signed out of session for ${session['browser']}'),
          backgroundColor: brandViolet,
        ),
      );
      return;
    }

    setState(() {
      _webSessions.removeAt(index);
    });
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Terminated session for ${session['browser']}'),
        backgroundColor: brandViolet,
        duration: const Duration(seconds: 2),
      ),
    );
    _loadBackendDevices();
  }

  void _showLogoutAllSessionsModal() {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.white,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) => SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 16, 24, 28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Center(
                child: Container(
                  width: 44,
                  height: 4,
                  decoration: BoxDecoration(
                    color: const Color(0xFFEAECEE),
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 20),
              Container(
                width: 56,
                height: 56,
                decoration: const BoxDecoration(
                  color: Color(0xFFE6F6EF),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.logout_rounded, color: brandViolet, size: 28),
              ),
              const SizedBox(height: 16),
              const Text(
                'Log out of all sessions?',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                  color: textDark,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'Sign out of all active browsers and sessions. Your registered mobile devices and login credentials will remain secure.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 12.5,
                  color: textGray,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: brandViolet,
                    foregroundColor: Colors.white,
                    elevation: 0,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                  ),
                  onPressed: () async {
                    Navigator.of(ctx).pop();

                    final currentType = AuthApiService().currentDeviceType.isNotEmpty
                        ? AuthApiService().currentDeviceType
                        : DeviceIdentity().type;
                    final isRunningOnWeb = kIsWeb || currentType.toUpperCase() == 'WEB';

                    if (isRunningOnWeb) {
                      // Disconnect notification stream first to avoid receiving self-revocation alerts
                      NotificationStreamService().disconnect();
                    }

                    // 1. Call backend API to terminate all web sessions cleanly
                    await AuthApiService().logoutAllSessions();

                    if (isRunningOnWeb) {
                      await AuthApiService().logout();
                      if (!mounted) return;
                      ScaffoldMessenger.of(context).clearSnackBars();
                      Navigator.of(context).pushAndRemoveUntil(
                        MaterialPageRoute(builder: (_) => const LoginScreen()),
                        (route) => false,
                      );
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('All web sessions have been logged out.'),
                          backgroundColor: brandViolet,
                        ),
                      );
                    } else {
                      setState(() {
                        _webSessions.clear();
                      });
                      if (!mounted) return;
                      ScaffoldMessenger.of(context).clearSnackBars();
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('All web sessions have been logged out.'),
                          backgroundColor: brandViolet,
                        ),
                      );
                      _loadBackendDevices();
                    }
                  },
                  child: const Text(
                    'Log Out All Sessions',
                    style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.bold),
                  ),
                ),
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                height: 48,
                child: TextButton(
                  style: TextButton.styleFrom(
                    backgroundColor: const Color(0xFFF1F3F4),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                  ),
                  onPressed: () => Navigator.of(ctx).pop(),
                  child: const Text(
                    'Cancel',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: textDark,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _showLogoutAllDevicesModal() {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.white,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) => SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 16, 24, 28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Center(
                child: Container(
                  width: 44,
                  height: 4,
                  decoration: BoxDecoration(
                    color: const Color(0xFFEAECEE),
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 20),
              Container(
                width: 56,
                height: 56,
                decoration: const BoxDecoration(
                  color: Color(0xFFE6F6EF),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.devices_other_rounded, color: brandViolet, size: 28),
              ),
              const SizedBox(height: 16),
              const Text(
                'Log out of all devices?',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                  color: textDark,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'This will remove both registered devices from your account. You will need your password and an OTP to sign in again.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 12.5,
                  color: textGray,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 18),
              if (_trustedDevices.isNotEmpty)
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: bgLavender,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: borderLavender),
                  ),
                  child: Column(
                    children: [
                      for (int i = 0; i < _trustedDevices.length; i++) ...[
                        if (i > 0) const Divider(color: borderLavender, height: 16),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Row(
                              children: [
                                Icon(
                                  _trustedDevices[i]['icon'] as IconData? ?? Icons.phone_iphone_rounded,
                                  size: 18,
                                  color: brandViolet,
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  _trustedDevices[i]['name'] as String? ?? 'Device',
                                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: textDark),
                                ),
                              ],
                            ),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                              decoration: BoxDecoration(
                                color: const Color(0xFFE6F6EF),
                                borderRadius: BorderRadius.circular(6),
                                border: Border.all(color: const Color(0xFFDDEAF3), width: 0.8),
                              ),
                              child: Text(
                                _trustedDevices[i]['isPrimary'] == true ? 'Primary' : 'Secondary',
                                style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w800, color: brandViolet),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ],
                  ),
                ),
              const SizedBox(height: 22),
              SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: brandViolet,
                    foregroundColor: Colors.white,
                    elevation: 0,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                  ),
                  onPressed: () async {
                    Navigator.of(ctx).pop();
                    NotificationStreamService().disconnect();
                    await AuthApiService().logoutAll();
                    await AuthApiService().logout();
                    if (!mounted) return;
                    ScaffoldMessenger.of(context).clearSnackBars();
                    Navigator.of(context).pushAndRemoveUntil(
                      MaterialPageRoute(builder: (_) => const LoginScreen()),
                      (route) => false,
                    );
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text('All devices signed out everywhere.'),
                        backgroundColor: brandViolet,
                      ),
                    );
                  },
                  child: const Text(
                    'Log Out Everywhere',
                    style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.bold),
                  ),
                ),
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                height: 48,
                child: TextButton(
                  style: TextButton.styleFrom(
                    backgroundColor: const Color(0xFFF1F3F4),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                  ),
                  onPressed: () => Navigator.of(ctx).pop(),
                  child: const Text(
                    'Cancel',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: textDark,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final currentId = AuthApiService().currentDeviceId.isNotEmpty
        ? AuthApiService().currentDeviceId
        : DeviceIdentity().id;
    final currentType = AuthApiService().currentDeviceType.isNotEmpty
        ? AuthApiService().currentDeviceType
        : DeviceIdentity().type;
    final isWebPersona = currentType.toUpperCase() == 'WEB';
    final isRunningOnWeb = kIsWeb && isWebPersona;

    final hasMatchingDeviceId = _trustedDevices.any((d) => d['id'] == currentId);
    final hasMatchingSessionId = _webSessions.any((w) => w['id'] == currentId);

    return Scaffold(
      backgroundColor: const Color(0xFFF7F7F7),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 12.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(
                children: [
                  Container(
                    width: 38,
                    height: 38,
                    decoration: BoxDecoration(
                      color: Colors.white,
                      shape: BoxShape.circle,
                      border: Border.all(color: borderLavender),
                      boxShadow: [
                        BoxShadow(
                          color: brandViolet.withValues(alpha: 0.04),
                          blurRadius: 4,
                          offset: const Offset(0, 2),
                        ),
                      ],
                    ),
                    child: IconButton(
                      icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 16, color: textDark),
                      onPressed: () => Navigator.of(context).maybePop(),
                      padding: EdgeInsets.zero,
                    ),
                  ),
                  const Expanded(
                    child: Center(
                      child: Text(
                        'Devices & Sessions',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w800,
                          letterSpacing: -0.4,
                          color: textDark,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 38), // Balance width for center alignment
                ],
              ),

              const SizedBox(height: 18),

              // Security Key Telemetry Banner
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(color: borderLavender, width: 1.2),
                  boxShadow: [
                    BoxShadow(
                      color: brandViolet.withValues(alpha: 0.04),
                      blurRadius: 12,
                      offset: const Offset(0, 3),
                    ),
                  ],
                ),
                child: Row(
                  children: [
                    Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: const Color(0xFFE6F6EF),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: const Icon(Icons.verified_user_rounded, color: brandViolet, size: 22),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: const [
                          Text(
                            'Device & Session Security',
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w800,
                              color: textDark,
                            ),
                          ),
                          SizedBox(height: 2),
                          Text(
                            'Your account is secured with verified device authorization and active session controls.',
                            style: TextStyle(
                              fontSize: 11,
                              color: textGray,
                              height: 1.3,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 22),

              // Section 1 Header: TRUSTED DEVICES (2/2)
              Row(
                children: [
                  Container(
                    width: 3.5,
                    height: 13,
                    decoration: BoxDecoration(
                      color: brandViolet,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    'TRUSTED DEVICES (${_trustedDevices.length}/2)',
                    style: const TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w800,
                      color: brandViolet,
                      letterSpacing: 0.8,
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 12),

              // Trusted Devices Cards
              if (_isLoading && _trustedDevices.isEmpty)
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 36),
                  margin: const EdgeInsets.only(bottom: 14),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: borderLavender),
                  ),
                  child: const Center(
                    child: CircularProgressIndicator(color: brandViolet, strokeWidth: 2.5),
                  ),
                )
              else if (_trustedDevices.isEmpty)
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
                  margin: const EdgeInsets.only(bottom: 14),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: borderLavender),
                  ),
                  child: Column(
                    children: const [
                      Icon(Icons.phonelink_lock_rounded, size: 36, color: brandViolet),
                      SizedBox(height: 10),
                      Text(
                        'No Trusted Devices Enrolled',
                        style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800, color: textDark),
                      ),
                      SizedBox(height: 4),
                      Text(
                        'Sign in on another mobile device to register it to your account.',
                        style: TextStyle(fontSize: 11.5, color: textGray),
                        textAlign: TextAlign.center,
                      ),
                    ],
                  ),
                )
              else
                ..._trustedDevices.asMap().entries.map((entry) {
                  final index = entry.key;
                  final dev = entry.value;
                  final isCurrentDevice = (dev['id'] == currentId);

                  return Container(
                    margin: const EdgeInsets.only(bottom: 14),
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                        color: (isCurrentDevice || dev['isPrimary'] == true)
                            ? brandViolet.withValues(alpha: 0.25)
                            : borderLavender,
                        width: 1.2,
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: brandViolet.withValues(alpha: 0.05),
                          blurRadius: 14,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: Column(
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Container(
                              width: 44,
                              height: 44,
                              decoration: BoxDecoration(
                                color: const Color(0xFFE6F6EF),
                                borderRadius: BorderRadius.circular(14),
                                border: Border.all(color: const Color(0xFFDDEAF3)),
                              ),
                              child: Icon(
                                dev['icon'] as IconData,
                                color: brandViolet,
                                size: 24,
                              ),
                            ),
                            const SizedBox(width: 14),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Wrap(
                                    crossAxisAlignment: WrapCrossAlignment.center,
                                    spacing: 6,
                                    runSpacing: 3,
                                    children: [
                                      Text(
                                        dev['name'] as String,
                                        style: const TextStyle(
                                          fontSize: 15,
                                          fontWeight: FontWeight.w800,
                                          color: textDark,
                                        ),
                                      ),
                                      if (isCurrentDevice)
                                        Container(
                                          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                                          decoration: BoxDecoration(
                                            color: const Color(0xFFE4F5EE),
                                            borderRadius: BorderRadius.circular(6),
                                            border: Border.all(color: const Color(0xFFA7E8D1), width: 0.8),
                                          ),
                                          child: const Text(
                                            '(Current device)',
                                            style: TextStyle(
                                              fontSize: 10.5,
                                              fontWeight: FontWeight.w700,
                                              color: Color(0xFF17805F),
                                            ),
                                          ),
                                        ),
                                    ],
                                  ),
                                  const SizedBox(height: 2),
                                  Text(
                                    dev['os'] as String,
                                    style: const TextStyle(
                                      fontSize: 11.5,
                                      fontWeight: FontWeight.w600,
                                      color: textGray,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Row(
                                    children: [
                                      const Icon(Icons.location_on_outlined, size: 12, color: textGray),
                                      const SizedBox(width: 3),
                                      Expanded(
                                        child: Text(
                                          dev['location'] as String,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(
                                            fontSize: 11,
                                            color: textGray,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                              decoration: BoxDecoration(
                                color: dev['isPrimary'] == true
                                    ? brandViolet
                                    : const Color(0xFFE6F6EF),
                                borderRadius: BorderRadius.circular(10),
                                border: dev['isPrimary'] == true
                                    ? null
                                    : Border.all(color: const Color(0xFFDDEAF3), width: 0.8),
                              ),
                              child: Text(
                                dev['isPrimary'] == true ? 'PRIMARY' : 'SECONDARY',
                                style: TextStyle(
                                  fontSize: 10,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: 0.8,
                                  color: dev['isPrimary'] == true ? Colors.white : brandViolet,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 14),
                        const Divider(color: Color(0xFFF1F3F4), height: 1),
                        const SizedBox(height: 10),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Row(
                              children: [
                                Icon(
                                  Icons.circle,
                                  size: 8,
                                  color: (isCurrentDevice || dev['isActive'] == true)
                                      ? const Color(0xFF2FA37E)
                                      : const Color(0xFF9AA3AB),
                                ),
                                const SizedBox(width: 6),
                                Text(
                                  isCurrentDevice ? 'Active Now' : (dev['status'] as String),
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.w700,
                                    color: (isCurrentDevice || dev['isActive'] == true)
                                        ? const Color(0xFF17805F)
                                        : const Color(0xFF7D8892),
                                  ),
                                ),
                              ],
                            ),
                            Row(
                              children: [
                                if (dev['isApproved'] == false && _isCallerPrimary) ...[
                                  InkWell(
                                    onTap: () async {
                                      final messenger = ScaffoldMessenger.of(context);
                                      final deviceId = dev['id'] as String?;
                                      if (deviceId != null && deviceId.isNotEmpty) {
                                        final ok = await AuthApiService().approveDevice(deviceId: deviceId);
                                        if (ok) {
                                          if (!mounted) return;
                                          messenger.showSnackBar(
                                            SnackBar(
                                              content: Text('Device ${dev['name']} approved successfully!'),
                                              backgroundColor: const Color(0xFF107C41),
                                            ),
                                          );
                                          _loadBackendDevices();
                                        }
                                      }
                                    },
                                    borderRadius: BorderRadius.circular(8),
                                    child: Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                                      margin: const EdgeInsets.only(right: 8),
                                      decoration: BoxDecoration(
                                        color: const Color(0xFF107C41),
                                        borderRadius: BorderRadius.circular(8),
                                      ),
                                      child: const Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Icon(Icons.check_circle_outline_rounded, size: 13, color: Colors.white),
                                          SizedBox(width: 4),
                                          Text(
                                            'Approve',
                                            style: TextStyle(
                                              fontSize: 11.5,
                                              fontWeight: FontWeight.w700,
                                              color: Colors.white,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ],
                                if (dev['isPrimary'] != true || _isCallerPrimary) ...[
                                  InkWell(
                                    onTap: () => _revokeDevice(index),
                                    borderRadius: BorderRadius.circular(8),
                                  child: Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                                    decoration: BoxDecoration(
                                      color: bgLavender,
                                      borderRadius: BorderRadius.circular(8),
                                      border: Border.all(color: borderLavender, width: 1.0),
                                    ),
                                    child: const Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Icon(Icons.link_off_rounded, size: 13, color: brandViolet),
                                        SizedBox(width: 4),
                                        Text(
                                          'Revoke',
                                          style: TextStyle(
                                            fontSize: 11.5,
                                            fontWeight: FontWeight.w700,
                                            color: brandViolet,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ),
                          ],
                        ),
                      ],
                    ),
                  );
                }),

              const SizedBox(height: 18),

              // Section 2 Header: ACTIVE WEB SESSIONS
              Row(
                children: [
                  Container(
                    width: 3.5,
                    height: 13,
                    decoration: BoxDecoration(
                      color: brandViolet,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const SizedBox(width: 8),
                  const Text(
                    'ACTIVE WEB SESSIONS',
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w800,
                      color: brandViolet,
                      letterSpacing: 0.8,
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 12),

              // Active & Pending Web Sessions
              if (_isLoading && _webSessions.isEmpty)
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 36),
                  margin: const EdgeInsets.only(bottom: 14),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: borderLavender),
                  ),
                  child: const Center(
                    child: CircularProgressIndicator(color: brandViolet, strokeWidth: 2.5),
                  ),
                )
              else if (_webSessions.isEmpty)
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
                  margin: const EdgeInsets.only(bottom: 14),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: borderLavender),
                  ),
                  child: Column(
                    children: const [
                      Icon(Icons.laptop_chromebook_rounded, size: 36, color: brandViolet),
                      SizedBox(height: 10),
                      Text(
                        'No active web sessions.',
                        style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800, color: textDark),
                      ),
                      SizedBox(height: 4),
                      Text(
                        'Active browser sessions will appear here when you sign in on the web.',
                        style: TextStyle(fontSize: 11.5, color: textGray),
                        textAlign: TextAlign.center,
                      ),
                    ],
                  ),
                )
              else
                ..._webSessions.asMap().entries.map((entry) {
                  final idx = entry.key;
                  final s = entry.value;
                  final isCurrentSession = (s['id'] == currentId);
                  final isPending = s['isApproved'] == false;

                  return Container(
                    margin: const EdgeInsets.only(bottom: 12),
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                        color: isCurrentSession
                            ? brandViolet.withValues(alpha: 0.25)
                            : borderLavender,
                        width: 1.2,
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: brandViolet.withValues(alpha: 0.04),
                          blurRadius: 14,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: Column(
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Container(
                              width: 44,
                              height: 44,
                              decoration: BoxDecoration(
                                color: const Color(0xFFE6F6EF),
                                borderRadius: BorderRadius.circular(14),
                                border: Border.all(color: const Color(0xFFDDEAF3)),
                              ),
                              child: Icon(
                                s['icon'] as IconData? ?? Icons.laptop_mac_rounded,
                                color: brandViolet,
                                size: 24,
                              ),
                            ),
                            const SizedBox(width: 14),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Wrap(
                                    crossAxisAlignment: WrapCrossAlignment.center,
                                    spacing: 6,
                                    runSpacing: 3,
                                    children: [
                                      Text(
                                        s['browser'] as String,
                                        style: const TextStyle(
                                          fontSize: 15,
                                          fontWeight: FontWeight.w800,
                                          color: textDark,
                                        ),
                                      ),
                                      if (isCurrentSession)
                                        Container(
                                          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                                          decoration: BoxDecoration(
                                            color: const Color(0xFFE4F5EE),
                                            borderRadius: BorderRadius.circular(6),
                                            border: Border.all(color: const Color(0xFFA7E8D1), width: 0.8),
                                          ),
                                          child: const Text(
                                            '(Current session)',
                                            style: TextStyle(
                                              fontSize: 10.5,
                                              fontWeight: FontWeight.w700,
                                              color: Color(0xFF17805F),
                                            ),
                                          ),
                                        ),
                                    ],
                                  ),
                                  const SizedBox(height: 2),
                                  Text(
                                    isPending ? 'Secondary Device (Pending Approval)' : 'Active Web Session',
                                    style: const TextStyle(
                                      fontSize: 11.5,
                                      fontWeight: FontWeight.w600,
                                      color: textGray,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Row(
                                    children: [
                                      const Icon(Icons.location_on_outlined, size: 12, color: textGray),
                                      const SizedBox(width: 3),
                                      Expanded(
                                        child: Text(
                                          s['location'] as String? ?? 'Web Session',
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(
                                            fontSize: 11,
                                            color: textGray,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                            if (!isPending)
                              OutlinedButton(
                                onPressed: () => _endWebSession(idx),
                                style: OutlinedButton.styleFrom(
                                  foregroundColor: isCurrentSession ? const Color(0xFFC8423B) : brandViolet,
                                  backgroundColor: isCurrentSession ? const Color(0xFFFDF3F2) : bgLavender,
                                  side: BorderSide(
                                    color: isCurrentSession ? const Color(0xFFF6CFCB) : borderLavender,
                                    width: 1.0,
                                  ),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                                  minimumSize: Size.zero,
                                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                                ),
                                child: Text(
                                  isCurrentSession ? 'Sign Out' : 'End',
                                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
                                ),
                              )
                            else
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                decoration: BoxDecoration(
                                  color: const Color(0xFFE6F6EF),
                                  borderRadius: BorderRadius.circular(10),
                                  border: Border.all(color: const Color(0xFFDDEAF3), width: 0.8),
                                ),
                                child: const Text(
                                  'SECONDARY',
                                  style: TextStyle(
                                    fontSize: 10,
                                    fontWeight: FontWeight.w800,
                                    letterSpacing: 0.8,
                                    color: brandViolet,
                                  ),
                                ),
                              ),
                          ],
                        ),
                        if (isPending) ...[
                          const SizedBox(height: 14),
                          const Divider(color: Color(0xFFF1F3F4), height: 1),
                          const SizedBox(height: 10),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              const Row(
                                children: [
                                  Icon(
                                    Icons.circle,
                                    size: 8,
                                    color: Color(0xFF9AA3AB),
                                  ),
                                  SizedBox(width: 6),
                                  Text(
                                    'Pending Approval',
                                    style: TextStyle(
                                      fontSize: 12,
                                      fontWeight: FontWeight.w700,
                                      color: Color(0xFF7D8892),
                                    ),
                                  ),
                                ],
                              ),
                              Row(
                                children: [
                                  if (_isCallerPrimary) ...[
                                    InkWell(
                                      onTap: () async {
                                        final messenger = ScaffoldMessenger.of(context);
                                        final deviceId = s['id'] as String?;
                                        if (deviceId != null && deviceId.isNotEmpty) {
                                          final ok = await AuthApiService().approveDevice(deviceId: deviceId);
                                          if (ok) {
                                            if (!mounted) return;
                                            messenger.showSnackBar(
                                              SnackBar(
                                                content: Text('Web session ${s['browser']} approved successfully!'),
                                                backgroundColor: const Color(0xFF107C41),
                                              ),
                                            );
                                            _loadBackendDevices();
                                          }
                                        }
                                      },
                                      borderRadius: BorderRadius.circular(8),
                                      child: Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                                        margin: const EdgeInsets.only(right: 8),
                                        decoration: BoxDecoration(
                                          color: const Color(0xFF107C41),
                                          borderRadius: BorderRadius.circular(8),
                                        ),
                                        child: const Row(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            Icon(Icons.check_circle_outline_rounded, size: 13, color: Colors.white),
                                            SizedBox(width: 4),
                                            Text(
                                              'Approve',
                                              style: TextStyle(
                                                fontSize: 11.5,
                                                fontWeight: FontWeight.w700,
                                                color: Colors.white,
                                              ),
                                            ),
                                          ],
                                        ),
                                      ),
                                    ),
                                  ],
                                  InkWell(
                                    onTap: () => _endWebSession(idx),
                                    borderRadius: BorderRadius.circular(8),
                                    child: Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                                      decoration: BoxDecoration(
                                        color: bgLavender,
                                        borderRadius: BorderRadius.circular(8),
                                        border: Border.all(color: borderLavender, width: 1.0),
                                      ),
                                      child: const Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Icon(Icons.link_off_rounded, size: 13, color: brandViolet),
                                          SizedBox(width: 4),
                                          Text(
                                            'Revoke',
                                            style: TextStyle(
                                              fontSize: 11.5,
                                              fontWeight: FontWeight.w700,
                                              color: brandViolet,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ],
                      ],
                    ),
                  );
                }),

              const SizedBox(height: 28),

              // Bottom Actions
              // 1. Log Out All Sessions (Aura Brand Outlined)
              SizedBox(
                width: double.infinity,
                height: 50,
                child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(
                    side: const BorderSide(color: borderLavender, width: 1.4),
                    backgroundColor: bgLavender,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
                    elevation: 0,
                  ),
                  onPressed: _showLogoutAllSessionsModal,
                  icon: const Icon(Icons.logout_rounded, size: 19, color: brandViolet),
                  label: const Text(
                    'Log Out All Sessions',
                    style: TextStyle(
                      fontSize: 14.5,
                      fontWeight: FontWeight.w800,
                      color: brandViolet,
                      letterSpacing: 0.2,
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 12),

              // 2. Log Out of All Devices (Aura Violet Text Action)
              Center(
                child: TextButton(
                  onPressed: _showLogoutAllDevicesModal,
                  child: const Text(
                    'Log Out of All Devices',
                    style: TextStyle(
                      fontSize: 13.5,
                      fontWeight: FontWeight.w700,
                      color: Color(0xFF2F78A8),
                      letterSpacing: 0.2,
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }
}
