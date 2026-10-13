import 'package:flutter/material.dart';
import '../services/bank_service.dart';
import '../theme/aura_theme.dart';

class BackendRoutingSheet extends StatefulWidget {
  const BackendRoutingSheet({super.key});

  static Future<void> show(BuildContext context) => showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        backgroundColor: Colors.transparent,
        builder: (_) => const BackendRoutingSheet(),
      );

  @override
  State<BackendRoutingSheet> createState() => _BackendRoutingSheetState();
}

class _BackendRoutingSheetState extends State<BackendRoutingSheet> {
  final _bankService = BankService();
  late final TextEditingController _cloudUrlController =
      TextEditingController(text: _bankService.cloudUrl);
  bool _isTesting = false;

  @override
  void dispose() {
    _cloudUrlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _bankService,
      builder: (context, _) {
        final isProd = _bankService.environment == AppEnvironment.prod;

        return Container(
          padding: EdgeInsets.only(
            left: 24,
            right: 24,
            top: 24,
            bottom: MediaQuery.of(context).viewInsets.bottom + 24,
          ),
          decoration: const BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
          ),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 40,
                    height: 4,
                    decoration: BoxDecoration(
                      color: Colors.black12,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                const SizedBox(height: 18),
                const Text(
                  'Backend & Cloud Environment',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w800,
                    color: AuraColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Toggle between local Docker development and live Azure Cloud production endpoints.',
                  style: TextStyle(fontSize: 12, color: AuraColors.textMuted, height: 1.4),
                ),
                const SizedBox(height: 18),

                // Option 1: Local Docker PC
                InkWell(
                  onTap: () => _bankService.setEnvironment(AppEnvironment.local),
                  borderRadius: BorderRadius.circular(14),
                  child: Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: !isProd ? const Color(0xFFEFF6FB) : const Color(0xFFF7F7F7),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: !isProd ? AuraColors.primary : AuraColors.cardBorder,
                        width: !isProd ? 1.5 : 1,
                      ),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          !isProd ? Icons.radio_button_checked_rounded : Icons.radio_button_off_rounded,
                          color: !isProd ? AuraColors.primary : AuraColors.textMuted,
                          size: 20,
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text(
                                'Local Docker PC (Development)',
                                style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w800, color: AuraColors.textPrimary),
                              ),
                              const SizedBox(height: 2),
                              Text(
                                _bankService.localUrl,
                                style: const TextStyle(fontSize: 11, color: AuraColors.textMuted),
                              ),
                            ],
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                          decoration: BoxDecoration(
                            color: !isProd ? AuraColors.primary : Colors.transparent,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            'DEFAULT',
                            style: TextStyle(
                              fontSize: 9.5,
                              fontWeight: FontWeight.w800,
                              color: !isProd ? Colors.white : Colors.transparent,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

                const SizedBox(height: 10),

                // Option 2: Azure Cloud Prod
                InkWell(
                  onTap: () => _bankService.setEnvironment(AppEnvironment.prod),
                  borderRadius: BorderRadius.circular(14),
                  child: Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: isProd ? const Color(0xFFEFF6FB) : const Color(0xFFF7F7F7),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: isProd ? AuraColors.primary : AuraColors.cardBorder,
                        width: isProd ? 1.5 : 1,
                      ),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          isProd ? Icons.radio_button_checked_rounded : Icons.radio_button_off_rounded,
                          color: isProd ? AuraColors.primary : AuraColors.textMuted,
                          size: 20,
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text(
                                'Azure Cloud (Production AKS)',
                                style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w800, color: AuraColors.textPrimary),
                              ),
                              const SizedBox(height: 2),
                              Text(
                                _bankService.cloudUrl,
                                style: const TextStyle(fontSize: 11, color: AuraColors.textMuted),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ],
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                          decoration: BoxDecoration(
                            color: isProd ? const Color(0xFF2FA37E) : Colors.transparent,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            'AZURE',
                            style: TextStyle(
                              fontSize: 9.5,
                              fontWeight: FontWeight.w800,
                              color: isProd ? Colors.white : Colors.transparent,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

                const SizedBox(height: 14),

                // Cloud Gateway Input Field (applied on Test Ping / Save & Close)
                if (isProd) ...[
                  const Text(
                    'Azure Cloud Gateway URL',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AuraColors.textPrimary),
                  ),
                  const SizedBox(height: 6),
                  TextField(
                    controller: _cloudUrlController,
                    decoration: InputDecoration(
                      hintText: 'https://gateway.banking.azure.com or IP',
                      filled: true,
                      fillColor: const Color(0xFFF7F7F7),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: const BorderSide(color: AuraColors.cardBorder),
                      ),
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                ],

                // Auto-Fallback Switch
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Auto-Fallback to Local Docker',
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AuraColors.textPrimary),
                          ),
                          SizedBox(height: 2),
                          Text(
                            'If cloud is offline or sleeping, fall back to local Docker PC seamlessly.',
                            style: TextStyle(fontSize: 11, color: AuraColors.textMuted),
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      value: _bankService.autoFallbackToLocal,
                      activeThumbColor: AuraColors.primary,
                      onChanged: _bankService.setAutoFallback,
                    ),
                  ],
                ),

                const SizedBox(height: 14),

                // Test Connection & Status
                if (_bankService.lastConnectionStatus != null) ...[
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                    decoration: BoxDecoration(
                      color: _bankService.lastPingLatencyMs != null
                          ? const Color(0xFFE4F5EE)
                          : const Color(0xFFFDF3F2),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: _bankService.lastPingLatencyMs != null
                            ? const Color(0xFF2FA37E).withValues(alpha: 0.3)
                            : const Color(0xFFD9534B).withValues(alpha: 0.3),
                      ),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          _bankService.lastPingLatencyMs != null ? Icons.check_circle_rounded : Icons.error_outline_rounded,
                          size: 16,
                          color: _bankService.lastPingLatencyMs != null ? const Color(0xFF17805F) : const Color(0xFFC8423B),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          'Status: ${_bankService.lastConnectionStatus}',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            color: _bankService.lastPingLatencyMs != null ? const Color(0xFF17805F) : const Color(0xFFC8423B),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                ],

                // Action Buttons
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        style: OutlinedButton.styleFrom(
                          side: const BorderSide(color: AuraColors.cardBorder),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        onPressed: _isTesting
                            ? null
                            : () async {
                                setState(() => _isTesting = true);
                                _bankService.setCloudUrl(_cloudUrlController.text);
                                await _bankService.testConnection();
                                if (mounted) setState(() => _isTesting = false);
                              },
                        child: _isTesting
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(strokeWidth: 2, color: AuraColors.primary),
                              )
                            : const Text(
                                'Test Ping',
                                style: TextStyle(fontWeight: FontWeight.w700, color: AuraColors.textPrimary),
                              ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AuraColors.primary,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        onPressed: () {
                          _bankService.setCloudUrl(_cloudUrlController.text);
                          Navigator.of(context).pop();
                        },
                        child: const Text(
                          'Save & Close',
                          style: TextStyle(fontWeight: FontWeight.w800, color: Colors.white),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
