import 'package:flutter/material.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_core/models/user_persona.dart' show DeviceIdentity;
import 'package:aurabank_core/theme/aura_theme.dart';

class WebProfileScreen extends StatefulWidget {
  final VoidCallback? onBack;

  const WebProfileScreen({super.key, this.onBack});

  @override
  State<WebProfileScreen> createState() => _WebProfileScreenState();
}

class _WebProfileScreenState extends State<WebProfileScreen> {
  final BankService _bankService = BankService();

  static const Color brandViolet = AuraColors.primary;
  static const Color textDark = AuraColors.textPrimary;
  static const Color textGray = AuraColors.textMuted;
  static const Color bgSurface = Color(0xFFF7F7F7);
  static const Color cardBorder = Color(0xFFEAECEE);
  static const Color accentGreen = Color(0xFF17805F);

  int _selectedNavSection = 0; // 0: Personal Information, 1: Security & 2FA, 2: Active Sessions

  // Form Controllers for interactive editing
  late TextEditingController _nameController;
  late TextEditingController _emailController;
  late TextEditingController _phoneController;
  late TextEditingController _addressController;

  bool _isSaving = false;
  bool _isLoadingDevices = false;

  final List<_PortalDevice> _devices = [];
  final List<_PortalDevice> _sessions = [];

  @override
  void initState() {
    super.initState();
    final user = _bankService.user;
    _nameController = TextEditingController(text: user.name);
    _emailController = TextEditingController(text: user.email);
    _phoneController = TextEditingController(text: user.phoneNumber);
    _addressController = TextEditingController(text: user.address);
    _bankService.addListener(_onServiceUpdate);
    _loadBackendDevices();
  }

  Future<void> _loadBackendDevices() async {
    setState(() => _isLoadingDevices = true);
    try {
      final devices = await AuthApiService().getRegisteredDevices();
      if (!mounted) return;
      final currentId = AuthApiService().currentDeviceId.isNotEmpty
          ? AuthApiService().currentDeviceId
          : DeviceIdentity().id;

      _devices.clear();
      _sessions.clear();

      for (final d in devices) {
        final type = (d['device_type'] as String? ?? 'MOBILE').toUpperCase();
        final isWeb = type == 'WEB';
        final isPrim = d['is_primary'] == true;
        final devId = d['device_id'] as String? ?? '';
        final devName = d['device_name'] as String? ?? (isWeb ? 'Web Browser' : 'Mobile Device');
        final ip = d['client_ip']?.toString() ?? '';
        final detail = ip.isNotEmpty ? 'IP: $ip' : (isWeb ? 'Web Session' : 'Registered Device');

        if (isWeb) {
          final isCurrent = devId == currentId;
          _sessions.add(_PortalDevice(
            id: devId,
            name: devName,
            detail: detail,
            badge: isCurrent ? 'THIS SESSION' : '',
            isThisBrowser: isCurrent,
            icon: Icons.laptop_mac_rounded,
          ));
        } else {
          _devices.add(_PortalDevice(
            id: devId,
            name: devName,
            detail: detail,
            badge: isPrim ? 'MAIN' : 'SECONDARY',
            isThisBrowser: false,
            icon: Icons.phone_iphone_rounded,
          ));
        }
      }
    } catch (_) {
    } finally {
      if (mounted) setState(() => _isLoadingDevices = false);
    }
  }

  @override
  void dispose() {
    _bankService.removeListener(_onServiceUpdate);
    _nameController.dispose();
    _emailController.dispose();
    _phoneController.dispose();
    _addressController.dispose();
    super.dispose();
  }

  void _onServiceUpdate() {
    if (mounted) setState(() {});
  }

  Future<void> _handleSaveProfile() async {
    setState(() => _isSaving = true);
    await Future.delayed(const Duration(milliseconds: 500));
    _bankService.updateUserProfile(
      name: _nameController.text.trim(),
      email: _emailController.text.trim(),
      phoneNumber: _phoneController.text.trim(),
      address: _addressController.text.trim(),
    );
    if (mounted) {
      setState(() => _isSaving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Account profile updated successfully'),
          backgroundColor: accentGreen,
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = _bankService.user;

    return Container(
      color: bgSurface,
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 28),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1200),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Security & Profile',
                  style: TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -0.5,
                    color: textDark,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Manage your personal details, password, and security preferences.',
                  style: TextStyle(
                    fontSize: 14,
                    color: textGray.withValues(alpha: 0.9),
                  ),
                ),

                const SizedBox(height: 32),

                // Settings Rail and Content Area
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // LEFT COLUMN: Settings Navigation Rail (280px)
                    SizedBox(
                      width: 280,
                      child: Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: cardBorder),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Compact Identity Header
                            Padding(
                              padding: const EdgeInsets.all(12),
                              child: Row(
                                children: [
                                  CircleAvatar(
                                    radius: 24,
                                    backgroundColor: brandViolet.withValues(alpha: 0.1),
                                    child: const Text(
                                      'RA',
                                      style: TextStyle(
                                        color: brandViolet,
                                        fontWeight: FontWeight.w800,
                                        fontSize: 16,
                                      ),
                                    ),
                                  ),
                                  const SizedBox(width: 12),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Text(
                                          user.name,
                                          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14, color: textDark),
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                        Text(
                                          _bankService.savingsAccountNumber,
                                          style: const TextStyle(fontSize: 11, color: textGray, fontFamily: 'monospace'),
                                        ),
                                      ],
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const Divider(height: 24, color: cardBorder),

                            _buildSettingsNavItem(0, Icons.person_outline_rounded, 'Personal Information'),
                            _buildSettingsNavItem(1, Icons.lock_outline_rounded, 'Security & Password'),
                            _buildSettingsNavItem(2, Icons.devices_rounded, 'Devices & Sessions'),
                          ],
                        ),
                      ),
                    ),

                    const SizedBox(width: 24),

                    // RIGHT COLUMN: Selected Section Detail
                    Expanded(
                      child: Container(
                        padding: const EdgeInsets.all(32),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(20),
                          border: Border.all(color: cardBorder),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withValues(alpha: 0.02),
                              blurRadius: 12,
                              offset: const Offset(0, 4),
                            ),
                          ],
                        ),
                        child: _buildSelectedSectionContent(),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSettingsNavItem(int index, IconData icon, String title) {
    final isSelected = _selectedNavSection == index;
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: InkWell(
        onTap: () => setState(() => _selectedNavSection = index),
        borderRadius: BorderRadius.circular(10),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: isSelected ? brandViolet.withValues(alpha: 0.08) : Colors.transparent,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            children: [
              Icon(icon, size: 18, color: isSelected ? brandViolet : textGray),
              const SizedBox(width: 12),
              Text(
                title,
                style: TextStyle(
                  fontSize: 13.5,
                  fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                  color: isSelected ? brandViolet : textDark,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSelectedSectionContent() {
    switch (_selectedNavSection) {
      case 0:
        return _buildPersonalInfoSection();
      case 1:
        return _buildSecuritySection();
      case 2:
        return _buildActiveSessionsSection();
      default:
        return _buildPersonalInfoSection();
    }
  }

  Widget _buildPersonalInfoSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Personal Details',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: textDark),
        ),
        const SizedBox(height: 4),
        const Text(
          'Update your personal information and verified contact channels.',
          style: TextStyle(fontSize: 13, color: textGray),
        ),
        const SizedBox(height: 28),

        Row(
          children: [
            Expanded(
              child: _buildInputField('Full Legal Name', _nameController, Icons.person_outline),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: _buildInputField('Email Address', _emailController, Icons.mail_outline),
            ),
          ],
        ),
        const SizedBox(height: 20),

        Row(
          children: [
            Expanded(
              child: _buildInputField('Phone Number', _phoneController, Icons.phone_outlined),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: _buildInputField('Billing Address', _addressController, Icons.location_on_outlined),
            ),
          ],
        ),
        const SizedBox(height: 32),

        Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            SizedBox(
              height: 44,
              child: OutlinedButton(
                onPressed: () {
                  final user = _bankService.user;
                  _nameController.text = user.name;
                  _emailController.text = user.email;
                  _phoneController.text = user.phoneNumber;
                  _addressController.text = user.address;
                },
                style: OutlinedButton.styleFrom(
                  side: const BorderSide(color: cardBorder),
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                ),
                child: const Text('Reset', style: TextStyle(color: textGray, fontWeight: FontWeight.w600)),
              ),
            ),
            const SizedBox(width: 12),
            SizedBox(
              height: 44,
              child: ElevatedButton(
                onPressed: _isSaving ? null : _handleSaveProfile,
                style: ElevatedButton.styleFrom(
                  backgroundColor: brandViolet,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(horizontal: 28),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                ),
                child: _isSaving
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                    : const Text('Save Changes', style: TextStyle(fontWeight: FontWeight.w700)),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildInputField(String label, TextEditingController controller, IconData icon) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: textDark),
        ),
        const SizedBox(height: 6),
        TextField(
          controller: controller,
          style: const TextStyle(fontSize: 14, color: textDark),
          decoration: InputDecoration(
            prefixIcon: Icon(icon, size: 18, color: textGray),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: const BorderSide(color: cardBorder),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: const BorderSide(color: cardBorder),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: const BorderSide(color: brandViolet, width: 1.5),
            ),
            contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          ),
        ),
      ],
    );
  }

  Widget _buildSecuritySection() {
    final user = _bankService.user;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Security & Password',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: textDark),
        ),
        const SizedBox(height: 4),
        const Text(
          'Manage your web sign-in password and security preferences.',
          style: TextStyle(fontSize: 13, color: textGray),
        ),
        const SizedBox(height: 28),

        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Text(
                    'Password',
                    style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14, color: textDark),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'Used to sign in and authorize web transfers.',
                    style: TextStyle(fontSize: 12.5, color: textGray),
                  ),
                ],
              ),
            ),
            OutlinedButton(
              onPressed: _showChangePasswordDialog,
              style: OutlinedButton.styleFrom(
                foregroundColor: brandViolet,
                side: const BorderSide(color: Color(0xFFDDD6FE)),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              ),
              child: const Text(
                'Change Password',
                style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
              ),
            ),
          ],
        ),
        const Divider(height: 32, color: cardBorder),

        _buildSecurityToggleTile(
          title: 'Notifications',
          subtitle: 'Receive alerts for account activity.',
          value: user.pushAlertsEnabled,
          onChanged: (val) {
            _bankService.setPushAlertsEnabled(val);
            setState(() {});
          },
        ),
      ],
    );
  }

  void _showChangePasswordDialog() {
    final currentPasswordController = TextEditingController();
    final newPasswordController = TextEditingController();
    final confirmPasswordController = TextEditingController();
    bool obscureCurrent = true;
    bool obscureNew = true;
    bool obscureConfirm = true;
    String? errorMessage;

    showDialog(
      context: context,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            return Dialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              backgroundColor: Colors.white,
              child: Container(
                width: 420,
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Container(
                          width: 40,
                          height: 40,
                          decoration: BoxDecoration(
                            color: brandViolet.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: const Icon(Icons.lock_reset_rounded, color: brandViolet, size: 22),
                        ),
                        const SizedBox(width: 12),
                        const Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Change Password',
                                style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800, color: textDark),
                              ),
                              Text(
                                'Update your web sign-in credentials',
                                style: TextStyle(fontSize: 12, color: textGray),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    if (errorMessage != null) ...[
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                        decoration: BoxDecoration(
                          color: const Color(0xFFFEF2F2),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: const Color(0xFFFCA5A5)),
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.error_outline_rounded, color: Color(0xFFDC2626), size: 16),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                errorMessage!,
                                style: const TextStyle(color: Color(0xFFDC2626), fontSize: 12, fontWeight: FontWeight.w600),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 12),
                    ],
                    TextField(
                      controller: currentPasswordController,
                      obscureText: obscureCurrent,
                      decoration: InputDecoration(
                        labelText: 'Current Password',
                        suffixIcon: IconButton(
                          icon: Icon(obscureCurrent ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
                          onPressed: () => setModalState(() => obscureCurrent = !obscureCurrent),
                        ),
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: newPasswordController,
                      obscureText: obscureNew,
                      decoration: InputDecoration(
                        labelText: 'New Password',
                        suffixIcon: IconButton(
                          icon: Icon(obscureNew ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
                          onPressed: () => setModalState(() => obscureNew = !obscureNew),
                        ),
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: confirmPasswordController,
                      obscureText: obscureConfirm,
                      decoration: InputDecoration(
                        labelText: 'Confirm New Password',
                        suffixIcon: IconButton(
                          icon: Icon(obscureConfirm ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
                          onPressed: () => setModalState(() => obscureConfirm = !obscureConfirm),
                        ),
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                      ),
                    ),
                    const SizedBox(height: 24),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton(
                          onPressed: () => Navigator.of(ctx).pop(),
                          child: const Text('Cancel', style: TextStyle(color: textGray, fontWeight: FontWeight.w600)),
                        ),
                        const SizedBox(width: 8),
                        ElevatedButton(
                          onPressed: () {
                            final current = currentPasswordController.text;
                            final next = newPasswordController.text;
                            final confirm = confirmPasswordController.text;
                            if (current.isEmpty || next.isEmpty) {
                              setModalState(() => errorMessage = 'Please enter all password fields');
                              return;
                            }
                            if (next.length < 6) {
                              setModalState(() => errorMessage = 'New password must be at least 6 characters');
                              return;
                            }
                            if (next != confirm) {
                              setModalState(() => errorMessage = 'New passwords do not match');
                              return;
                            }
                            Navigator.of(ctx).pop();
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                content: Text('Password updated successfully'),
                                backgroundColor: accentGreen,
                                behavior: SnackBarBehavior.floating,
                              ),
                            );
                          },
                          style: ElevatedButton.styleFrom(
                            backgroundColor: brandViolet,
                            foregroundColor: Colors.white,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                          ),
                          child: const Text('Update Password', style: TextStyle(fontWeight: FontWeight.w700)),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  Widget _buildSecurityToggleTile({
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14, color: textDark)),
              const SizedBox(height: 2),
              Text(subtitle, style: const TextStyle(fontSize: 12.5, color: textGray)),
            ],
          ),
        ),
        Switch.adaptive(
          value: value,
          activeTrackColor: brandViolet,
          onChanged: onChanged,
        ),
      ],
    );
  }

  Widget _buildActiveSessionsSection() {
    if (_isLoadingDevices) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(40.0),
          child: CircularProgressIndicator(color: brandViolet, strokeWidth: 2.5),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Connected Devices & Sessions',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: textDark),
        ),
        const SizedBox(height: 4),
        const Text(
          'Registered devices and sign-ins that are open right now.',
          style: TextStyle(fontSize: 13, color: textGray),
        ),
        const SizedBox(height: 28),
        _buildEntryGroup(
          title: 'Devices',
          subtitle: 'Phones and computers registered to this account. Up to two: main and secondary.',
          entries: _devices,
          emptyLabel: 'No registered devices.',
        ),
        const SizedBox(height: 28),
        _buildEntryGroup(
          title: 'Sessions',
          subtitle: 'Sign-ins that are open right now.',
          entries: _sessions,
          emptyLabel: 'No open sessions.',
        ),
      ],
    );
  }

  Widget _buildEntryGroup({
    required String title,
    required String subtitle,
    required List<_PortalDevice> entries,
    required String emptyLabel,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: textDark)),
        const SizedBox(height: 2),
        Text(subtitle, style: const TextStyle(fontSize: 12.5, color: textGray)),
        const SizedBox(height: 12),
        if (entries.isEmpty)
          Text(emptyLabel, style: const TextStyle(fontSize: 13, color: textGray))
        else
          for (var i = 0; i < entries.length; i++) ...[
            if (i > 0) const SizedBox(height: 12),
            _buildSessionCard(entries[i], entries),
          ],
      ],
    );
  }

  Future<void> _confirmRevoke(_PortalDevice entry, List<_PortalDevice> entries) async {
    final isSession = identical(entries, _sessions);

    // Desktop/web sessions CANNOT revoke primary devices
    if (entry.badge == 'MAIN' || entry.badge == 'PRIMARY') {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Secondary and desktop devices cannot revoke the primary device.'),
          backgroundColor: Color(0xFFC8423B),
        ),
      );
      return;
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          isSession ? 'Revoke this session?' : 'Revoke this device?',
          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: textDark),
        ),
        content: Text(
          isSession
              ? '${entry.name} will be signed out.'
              : '${entry.name} will be removed from this account.',
          style: const TextStyle(fontSize: 13.5, color: textGray),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel', style: TextStyle(color: textDark, fontWeight: FontWeight.w600)),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Revoke', style: TextStyle(color: Color(0xFFDC2626), fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    if (entry.isThisBrowser) {
      if (entry.id.isNotEmpty) {
        await AuthApiService().revokeDevice(deviceId: entry.id);
      }
      if (!mounted) return;
      Navigator.of(context).pushReplacementNamed('/login');
      return;
    }
    if (entry.id.isNotEmpty) {
      await AuthApiService().revokeDevice(deviceId: entry.id);
    }
    setState(() {
      entries.remove(entry);
    });
  }

  Widget _buildSessionCard(_PortalDevice entry, List<_PortalDevice> entries) {
    final highlighted = entry.badge == 'MAIN' || entry.isThisBrowser;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: highlighted ? brandViolet.withValues(alpha: 0.03) : const Color(0xFFF7F7F7),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: highlighted ? brandViolet.withValues(alpha: 0.2) : cardBorder),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: highlighted ? brandViolet.withValues(alpha: 0.1) : Colors.white,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(entry.icon, color: highlighted ? brandViolet : textGray, size: 20),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        entry.name,
                        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5, color: textDark),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (entry.badge.isNotEmpty) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                        decoration: BoxDecoration(
                          color: entry.badge == 'MAIN' || entry.isThisBrowser
                              ? const Color(0xFFE4F5EE)
                              : const Color(0xFFF3F4F6),
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          entry.badge,
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.w800,
                            color: entry.badge == 'MAIN' || entry.isThisBrowser ? accentGreen : textGray,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 2),
                Text(entry.detail, style: const TextStyle(fontSize: 12, color: textGray)),
              ],
            ),
          ),
          const SizedBox(width: 12),
          TextButton(
            onPressed: () => _confirmRevoke(entry, entries),
            style: TextButton.styleFrom(
              foregroundColor: const Color(0xFFDC2626),
              backgroundColor: const Color(0xFFFEF2F2),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('Revoke', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }

}

class _PortalDevice {
  _PortalDevice({
    this.id = '',
    required this.name,
    required this.detail,
    required this.badge,
    required this.isThisBrowser,
    required this.icon,
  });

  final String id;
  final String name;
  final String detail;
  String badge;
  final bool isThisBrowser;
  final IconData icon;
}
