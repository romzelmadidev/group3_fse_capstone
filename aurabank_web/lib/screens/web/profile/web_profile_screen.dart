import 'package:flutter/material.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_core/models/bank_models.dart' show TransferLimits;
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

  int _selectedNavSection = 0; // 0: Personal Info, 1: Security & 2FA, 2: Active Sessions, 3: Transfer Limits

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
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _bankService.loadLimits();
    });
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
                  'Account & Security Management',
                  style: TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -0.5,
                    color: textDark,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Manage institutional profile settings, Multi-Factor Authentication, and active desktop sessions.',
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
                            _buildSettingsNavItem(1, Icons.shield_outlined, 'Security & 2FA'),
                            _buildSettingsNavItem(2, Icons.devices_rounded, 'Devices & Sessions'),
                            _buildSettingsNavItem(3, Icons.account_balance_wallet_outlined, 'Transfer Limits'),
                            const SizedBox(height: 14),
                            _buildTransferLimitsRailCard(),
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
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                    color: isSelected ? brandViolet : textDark,
                  ),
                  overflow: TextOverflow.ellipsis,
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
      case 3:
        return _buildTransferLimitsSection();
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
          'Security',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: textDark),
        ),
        const SizedBox(height: 4),
        const Text(
          'Manage sign-in, transfer approval, and alerts.',
          style: TextStyle(fontSize: 13, color: textGray),
        ),
        const SizedBox(height: 28),

        _buildSecurityToggleTile(
          title: 'Face ID',
          subtitle: 'Sign in and authorize transfers with Face ID.',
          value: user.faceIdEnabled,
          onChanged: (val) {
            _bankService.setFaceIdEnabled(val);
            setState(() {});
          },
        ),
        const Divider(height: 24, color: cardBorder),

        _buildSecurityToggleTile(
          title: 'Fingerprint',
          subtitle: 'Sign in and authorize transfers with your fingerprint.',
          value: user.fingerprintEnabled,
          onChanged: (val) {
            _bankService.setFingerprintEnabled(val);
            setState(() {});
          },
        ),
        const Divider(height: 24, color: cardBorder),

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

  String _limitMoney(double v) {
    final fixed = v.toStringAsFixed(2).split('.');
    final whole = fixed[0].replaceAllMapped(
        RegExp(r'(\d{1,3})(?=(\d{3})+(?!\d))'), (m) => '${m[1]},');
    return '₱$whole.${fixed[1]}';
  }

  Widget _buildTransferLimitsRailCard() {
    final limits = _bankService.limits;
    final loading = _bankService.limitsLoading;
    final restricted = limits?.restricted ?? false;
    final isBasic = limits?.isBasic ?? true;
    final badgeLabel = limits == null
        ? (loading ? 'Loading...' : 'Unavailable')
        : (restricted ? 'Restricted' : (isBasic ? 'Basic' : 'Fully Verified'));
    final badgeFg = restricted
        ? AuraColors.debitRed
        : (limits != null && isBasic ? AuraColors.primary : AuraColors.creditGreen);
    final badgeBg = restricted
        ? AuraColors.debitRedBg
        : (limits != null && isBasic ? AuraColors.tintPurple : AuraColors.creditGreenBg);

    return InkWell(
      onTap: () => setState(() => _selectedNavSection = 3),
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0xFFF9FAFB),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: cardBorder),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.shield_outlined, size: 14, color: brandViolet),
                const SizedBox(width: 6),
                const Expanded(
                  child: Text(
                    'TRANSFER LIMITS',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 0.8,
                      color: textGray,
                    ),
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: badgeBg,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    badgeLabel,
                    style: TextStyle(
                      fontSize: 9.5,
                      fontWeight: FontWeight.w700,
                      color: badgeFg,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (limits != null && !restricted) ...[
              Text(
                'Daily remaining',
                style: TextStyle(fontSize: 11, color: textGray.withValues(alpha: 0.9)),
              ),
              const SizedBox(height: 2),
              Text(
                _limitMoney(limits.dailyWindow.remaining),
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: textDark,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'Per transfer cap',
                style: TextStyle(fontSize: 11, color: textGray.withValues(alpha: 0.9)),
              ),
              const SizedBox(height: 2),
              Text(
                _limitMoney(limits.perTransferCap),
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  color: textDark,
                ),
              ),
            ] else if (restricted) ...[
              const Text(
                'Transfers restricted. Review needed.',
                style: TextStyle(fontSize: 11, color: AuraColors.debitRed, fontWeight: FontWeight.w600),
              ),
            ] else ...[
              const Text(
                'View KYC velocity caps and allowances.',
                style: TextStyle(fontSize: 11, color: textGray),
              ),
            ],
            const SizedBox(height: 10),
            Row(
              children: const [
                Text(
                  'Manage Limits',
                  style: TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    color: brandViolet,
                  ),
                ),
                SizedBox(width: 4),
                Icon(Icons.arrow_forward_rounded, size: 12, color: brandViolet),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTransferLimitsSection() {
    final limits = _bankService.limits;
    final loading = _bankService.limitsLoading;
    final failed = _bankService.limitsError;
    final restricted = limits?.restricted ?? false;
    final isBasic = limits?.isBasic ?? false;

    final badgeLabel = limits == null
        ? null
        : (restricted ? 'Restricted' : (isBasic ? 'Basic Tier' : 'Fully Verified'));
    final badgeFg = restricted
        ? AuraColors.debitRed
        : (isBasic ? AuraColors.primary : AuraColors.creditGreen);
    final badgeBg = restricted
        ? AuraColors.debitRedBg
        : (isBasic ? AuraColors.tintPurple : AuraColors.creditGreenBg);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Section Header
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: 12,
                    runSpacing: 8,
                    children: [
                      const Text(
                        'Transfer Limits & Tiering',
                        style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: textDark),
                      ),
                      if (badgeLabel != null)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: badgeBg,
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Text(
                            badgeLabel,
                            style: TextStyle(
                              fontSize: 11.5,
                              fontWeight: FontWeight.w800,
                              color: badgeFg,
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Your transfer caps are set according to regulatory KYC requirements and rolling usage windows.',
                    style: TextStyle(fontSize: 13, color: textGray),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 16),
            SizedBox(
              height: 38,
              child: OutlinedButton.icon(
                onPressed: loading ? null : () => _bankService.loadLimits(),
                icon: loading
                    ? const SizedBox(
                        width: 14,
                        height: 14,
                        child: CircularProgressIndicator(strokeWidth: 2, color: brandViolet),
                      )
                    : const Icon(Icons.refresh_rounded, size: 16, color: brandViolet),
                label: Text(
                  loading ? 'Refreshing...' : 'Refresh',
                  style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700, color: brandViolet),
                ),
                style: OutlinedButton.styleFrom(
                  side: const BorderSide(color: cardBorder),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  padding: const EdgeInsets.symmetric(horizontal: 14),
                ),
              ),
            ),
          ],
        ),

        const SizedBox(height: 28),

        // Body States
        if (limits == null && loading) ...[
          Container(
            padding: const EdgeInsets.all(40),
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: const Color(0xFFF9FAFB),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: cardBorder),
            ),
            child: const Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.5, color: brandViolet),
                ),
                SizedBox(width: 14),
                Text(
                  'Loading your transfer limits and usage data...',
                  style: TextStyle(fontSize: 13.5, color: textGray, fontWeight: FontWeight.w500),
                ),
              ],
            ),
          ),
        ] else if (limits == null) ...[
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: AuraColors.debitRedBg,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: AuraColors.debitRed.withValues(alpha: 0.3)),
            ),
            child: Row(
              children: [
                const Icon(Icons.cloud_off_rounded, color: AuraColors.debitRed, size: 28),
                const SizedBox(width: 16),
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Unable to load transfer limits',
                        style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AuraColors.debitRed),
                      ),
                      SizedBox(height: 2),
                      Text(
                        'We could not reach the account service to fetch your latest caps. Please check your connection and retry.',
                        style: TextStyle(fontSize: 12.5, color: textDark),
                      ),
                    ],
                  ),
                ),
                ElevatedButton(
                  onPressed: () => _bankService.loadLimits(),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AuraColors.debitRed,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  ),
                  child: const Text('Retry', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
                ),
              ],
            ),
          ),
        ] else ...[
          if (restricted)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(18),
              margin: const EdgeInsets.only(bottom: 24),
              decoration: BoxDecoration(
                color: AuraColors.debitRedBg,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: AuraColors.debitRed.withValues(alpha: 0.35)),
              ),
              child: const Row(
                children: [
                  Icon(Icons.gpp_bad_rounded, color: AuraColors.debitRed, size: 28),
                  SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Transfers are restricted on this account',
                          style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800, color: AuraColors.debitRed),
                        ),
                        SizedBox(height: 3),
                        Text(
                          'Your account has been temporarily restricted from sending money. Please complete identity verification or contact Aura Bank compliance.',
                          style: TextStyle(fontSize: 12.5, color: textDark, height: 1.35),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),

          if (isBasic && !restricted)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(18),
              margin: const EdgeInsets.only(bottom: 24),
              decoration: BoxDecoration(
                color: AuraColors.tintPurple,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: brandViolet.withValues(alpha: 0.2)),
              ),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: brandViolet.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: const Icon(Icons.verified_user_outlined, color: brandViolet, size: 24),
                  ),
                  const SizedBox(width: 16),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Upgrade to Fully Verified for higher allowances',
                          style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800, color: textDark),
                        ),
                        SizedBox(height: 3),
                        Text(
                          'Basic accounts are capped at ₱10,000 per transfer and ₱20,000 daily. Verify your identity with a valid government ID to unlock up to ₱50,000 per transfer and ₱100,000 daily.',
                          style: TextStyle(fontSize: 12.5, color: textGray, height: 1.35),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),

          // 3 Metric Cards Grid
          LayoutBuilder(builder: (context, constraints) {
            final isNarrow = constraints.maxWidth < 750;
            final cards = [
              _buildLimitMetricCard(
                title: 'Per-Transaction Limit',
                badge: 'Single Send',
                amount: _limitMoney(limits.perTransferCap),
                subtitle: 'Maximum allowed per single fund transfer',
                icon: Icons.speed_rounded,
                iconColor: brandViolet,
                iconBg: brandViolet.withValues(alpha: 0.08),
                footer: const Text(
                  'Applies to InstaPay, PESONet & Aura Direct',
                  style: TextStyle(fontSize: 11, color: textGray),
                ),
              ),
              _buildLimitMetricCard(
                title: 'Daily Remaining',
                badge: '24h Rolling',
                amount: _limitMoney(limits.dailyWindow.remaining),
                subtitle: 'of ${_limitMoney(limits.dailyWindow.cap)} total daily cap',
                icon: Icons.today_rounded,
                iconColor: const Color(0xFF0D9488),
                iconBg: const Color(0xFFCCFBF1),
                progressUsed: limits.dailyWindow.used,
                progressCap: limits.dailyWindow.cap,
                footer: Text(
                  '${_limitMoney(limits.dailyWindow.used)} used today • Resets at 00:00',
                  style: const TextStyle(fontSize: 11, color: textGray),
                ),
              ),
              _buildLimitMetricCard(
                title: 'Monthly Remaining',
                badge: 'Calendar Month',
                amount: _limitMoney(limits.monthlyWindow.remaining),
                subtitle: 'of ${_limitMoney(limits.monthlyWindow.cap)} monthly cap',
                icon: Icons.calendar_month_rounded,
                iconColor: const Color(0xFF2563EB),
                iconBg: const Color(0xFFDBEAFE),
                progressUsed: limits.monthlyWindow.used,
                progressCap: limits.monthlyWindow.cap,
                footer: Text(
                  '${_limitMoney(limits.monthlyWindow.used)} used • Resets 1st of month',
                  style: const TextStyle(fontSize: 11, color: textGray),
                ),
              ),
            ];

            if (isNarrow) {
              return Column(
                children: cards.map((c) => Padding(padding: const EdgeInsets.only(bottom: 16), child: c)).toList(),
              );
            }
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: cards[0]),
                const SizedBox(width: 16),
                Expanded(child: cards[1]),
                const SizedBox(width: 16),
                Expanded(child: cards[2]),
              ],
            );
          }),

          if (failed)
            Padding(
              padding: const EdgeInsets.only(top: 14),
              child: Row(
                children: [
                  const Icon(Icons.info_outline_rounded, size: 16, color: Color(0xFFF59E0B)),
                  const SizedBox(width: 8),
                  const Text(
                    "Showing last loaded limits. Background refresh failed.",
                    style: TextStyle(fontSize: 12, color: textGray),
                  ),
                  const Spacer(),
                  InkWell(
                    onTap: () => _bankService.loadLimits(),
                    child: const Text(
                      'Retry Now',
                      style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: brandViolet),
                    ),
                  ),
                ],
              ),
            ),

          const SizedBox(height: 32),

          // Detailed Tier Comparison Table
          _buildTierComparisonTable(limits),

          const SizedBox(height: 24),

          // Compliance & Regulatory Notice Footer
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFFF9FAFB),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: cardBorder),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: const [
                Icon(Icons.verified_outlined, size: 18, color: textGray),
                SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Regulatory Compliance Note: Transfer limits are calibrated in compliance with Bangko Sentral ng Pilipinas (BSP) Circular No. 982 and the Anti-Money Laundering Act (AMLA). Limits refresh dynamically against the T24 CBS core ledger upon real-time settlement.',
                    style: TextStyle(fontSize: 12, color: textGray, height: 1.45),
                  ),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildLimitMetricCard({
    required String title,
    required String badge,
    required String amount,
    required String subtitle,
    required IconData icon,
    required Color iconColor,
    required Color iconBg,
    double? progressUsed,
    double? progressCap,
    required Widget footer,
  }) {
    final double? ratio = (progressUsed != null && progressCap != null && progressCap > 0)
        ? (progressUsed / progressCap).clamp(0.0, 1.0)
        : null;

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: cardBorder),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.02),
            blurRadius: 10,
            offset: const Offset(0, 3),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: iconBg,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: iconColor, size: 18),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: const Color(0xFFF3F4F6),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  badge,
                  style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: textGray),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            title,
            style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: textGray),
          ),
          const SizedBox(height: 4),
          Text(
            amount,
            style: const TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w800,
              color: textDark,
              letterSpacing: -0.4,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            subtitle,
            style: const TextStyle(fontSize: 11.5, color: textGray),
          ),
          if (ratio != null) ...[
            const SizedBox(height: 14),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: ratio,
                minHeight: 6,
                backgroundColor: const Color(0xFFF1F3F5),
                valueColor: AlwaysStoppedAnimation<Color>(
                  ratio > 0.85 ? const Color(0xFFEF4444) : (ratio > 0.6 ? const Color(0xFFF59E0B) : brandViolet),
                ),
              ),
            ),
          ],
          const SizedBox(height: 14),
          const Divider(height: 1, color: cardBorder),
          const SizedBox(height: 10),
          footer,
        ],
      ),
    );
  }

  Widget _buildTierComparisonTable(TransferLimits limits) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: cardBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: const [
                Text(
                  'Tier Comparison & Privileges',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: textDark),
                ),
                SizedBox(height: 3),
                Text(
                  'Limits correspond to verification tier requirements defined by banking regulations.',
                  style: TextStyle(fontSize: 12.5, color: textGray),
                ),
              ],
            ),
          ),
          const Divider(height: 1, color: cardBorder),
          // Table Header
          Container(
            color: const Color(0xFFF9FAFB),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            child: const Row(
              children: [
                Expanded(flex: 3, child: Text('TIER LEVEL', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: textGray, letterSpacing: 0.5), overflow: TextOverflow.ellipsis)),
                Expanded(flex: 3, child: Text('REQUIREMENTS', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: textGray, letterSpacing: 0.5), overflow: TextOverflow.ellipsis)),
                Expanded(flex: 2, child: Text('PER SEND', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: textGray, letterSpacing: 0.5), overflow: TextOverflow.ellipsis)),
                Expanded(flex: 2, child: Text('DAILY CAP', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: textGray, letterSpacing: 0.5), overflow: TextOverflow.ellipsis)),
                Expanded(flex: 2, child: Text('MONTHLY', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: textGray, letterSpacing: 0.5), overflow: TextOverflow.ellipsis)),
                Expanded(flex: 2, child: Text('STATUS', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: textGray, letterSpacing: 0.5), overflow: TextOverflow.ellipsis)),
              ],
            ),
          ),
          const Divider(height: 1, color: cardBorder),
          // Basic Row
          _buildTierTableRow(
            tierName: 'Basic Tier',
            requirements: 'Phone + Basic Info',
            perTransfer: '₱10,000.00',
            daily: '₱20,000.00',
            monthly: '₱100,000.00',
            isCurrent: limits.isBasic && !limits.restricted,
            isRestricted: limits.restricted,
          ),
          const Divider(height: 1, color: cardBorder),
          // Fully Verified Row
          _buildTierTableRow(
            tierName: 'Fully Verified',
            requirements: 'Government ID + Biometrics',
            perTransfer: '₱50,000.00',
            daily: '₱100,000.00',
            monthly: '₱500,000.00',
            isCurrent: !limits.isBasic && !limits.restricted,
            isRestricted: false,
          ),
        ],
      ),
    );
  }

  Widget _buildTierTableRow({
    required String tierName,
    required String requirements,
    required String perTransfer,
    required String daily,
    required String monthly,
    required bool isCurrent,
    required bool isRestricted,
  }) {
    return Container(
      color: isCurrent ? brandViolet.withValues(alpha: 0.04) : Colors.transparent,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      child: Row(
        children: [
          Expanded(
            flex: 3,
            child: Text(
              tierName,
              style: TextStyle(
                fontSize: 13,
                fontWeight: isCurrent ? FontWeight.w800 : FontWeight.w600,
                color: textDark,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          Expanded(
            flex: 3,
            child: Text(
              requirements,
              style: const TextStyle(fontSize: 12, color: textGray),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              perTransfer,
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: textDark),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              daily,
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: textDark),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              monthly,
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: textDark),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          Expanded(
            flex: 2,
            child: isCurrent
                ? Align(
                    alignment: Alignment.centerLeft,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: const Color(0xFFE4F5EE),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: const Text(
                        'CURRENT TIER',
                        style: TextStyle(
                          fontSize: 9.5,
                          fontWeight: FontWeight.w800,
                          color: Color(0xFF17805F),
                        ),
                      ),
                    ),
                  )
                : const Text('—', style: TextStyle(color: textGray, fontSize: 13)),
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
