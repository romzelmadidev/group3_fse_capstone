import 'package:flutter/material.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_core/widgets/aura_app_mark.dart';

class WebSidebar extends StatelessWidget {
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final VoidCallback? onLogout;

  const WebSidebar({
    super.key,
    required this.selectedIndex,
    required this.onDestinationSelected,
    this.onLogout,
  });

  static const Color bgDark = Color(0xFF0F0728);
  static const Color activeItemBg = Color(0xFF261250);
  static const Color borderSubtle = Color(0xFF1E103F);

  String _formatCurrency(double amount) {
    final parts = amount.toStringAsFixed(2).split('.');
    final reg = RegExp(r'(\d{1,3})(?=(\d{3})+(?!\d))');
    final formattedInt = parts[0].replaceAllMapped(reg, (Match m) => '${m[1]},');
    return '₱$formattedInt.${parts[1]}';
  }

  @override
  Widget build(BuildContext context) {
    final bank = BankService();
    return ListenableBuilder(
      listenable: bank,
      builder: (context, _) => _buildSidebar(bank),
    );
  }

  Widget _buildSidebar(BankService bank) {
    final userName = bank.user.name.isNotEmpty ? bank.user.name : 'Elijah Montefalco';
    final digits = bank.savingsAccountNumber.replaceAll(RegExp(r'\D'), '');
    final last4 = digits.length >= 4 ? digits.substring(digits.length - 4) : digits;

    return Container(
      width: 260,
      decoration: const BoxDecoration(
        color: bgDark,
        border: Border(
          right: BorderSide(color: borderSubtle, width: 1.0),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 1. Aura Bank Brand Header
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 28, 24, 20),
            child: Row(
              children: [
                const AuraAppMark(size: 42, borderRadius: 12),
                const SizedBox(width: 12),
                const Text(
                  'AURA BANK',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 15,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 1.2,
                  ),
                ),
              ],
            ),
          ),

          const Divider(color: borderSubtle, height: 1),
          const SizedBox(height: 14),

          // Primary Account Mini Card (as shown in prototype)
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFFFAF5FF),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFFE9D5FF)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Savings',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: Color(0xFF6B21A8),
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  '•••• $last4',
                  style: const TextStyle(
                    fontSize: 11,
                    color: Color(0xFF6B7280),
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  _formatCurrency(bank.availableBalance),
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w800,
                    color: Color(0xFF1E103F),
                    letterSpacing: -0.3,
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 14),
          const Divider(color: borderSubtle, height: 1),
          const SizedBox(height: 12),

          Expanded(
            child: ListView(
              padding: const EdgeInsets.symmetric(horizontal: 14),
              children: [
                _buildNavItem(index: 0, icon: Icons.home_rounded, label: 'Home'),
                _buildNavItem(index: 1, icon: Icons.swap_horiz_rounded, label: 'Transfer'),
                _buildNavItem(index: 2, icon: Icons.credit_card_rounded, label: 'Cards'),
                _buildNavItem(index: 3, icon: Icons.qr_code_scanner_rounded, label: 'Pay'),
                _buildNavItem(index: 4, icon: Icons.bar_chart_rounded, label: 'Reports'),
                _buildNavItem(index: 5, icon: Icons.person_rounded, label: 'Profile'),
              ],
            ),
          ),

          // 3. User Profile & Quick Logout Footer
          Container(
            padding: const EdgeInsets.all(16),
            margin: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: const Color(0xFF180D3A),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: borderSubtle),
            ),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 19,
                  backgroundColor: const Color(0xFF380084),
                  child: Text(
                    userName.isNotEmpty ? userName[0].toUpperCase() : 'E',
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w800,
                      fontSize: 14,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    userName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                IconButton(
                  tooltip: 'Sign out',
                  icon: const Icon(Icons.logout_rounded, color: Color(0xFFD1D5DB), size: 19),
                  onPressed: onLogout ?? () {},
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNavItem({
    required int index,
    required IconData icon,
    required String label,
  }) {
    final isSelected = selectedIndex == index;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: () => onDestinationSelected(index),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
            decoration: BoxDecoration(
              color: isSelected ? activeItemBg : Colors.transparent,
              borderRadius: BorderRadius.circular(12),
              border: isSelected
                  ? Border.all(color: const Color(0xFF8B5CF6).withValues(alpha: 0.5), width: 1.0)
                  : null,
            ),
            child: Row(
              children: [
                Icon(
                  icon,
                  size: 20,
                  color: isSelected ? const Color(0xFFA78BFA) : const Color(0xFF9CA3AF),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    label,
                    style: TextStyle(
                      fontSize: 13.5,
                      fontWeight: isSelected ? FontWeight.w800 : FontWeight.w600,
                      color: isSelected ? Colors.white : const Color(0xFFD1D5DB),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
