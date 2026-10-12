import 'dart:convert';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:aurabank_core/widgets/aura_logo.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/device_storage.dart';
import 'web_statement_screen.dart';
import 'web_annual_report_screen.dart';

/// Data point model for transfer flow points
class FlowPointData {
  final String label;
  final String shortLabel;
  final String dateSubtitle;
  final double received;
  final double sent;
  final int inTransfers;
  final int outTransfers;
  final List<AnalyticsTxItem> transactions;

  const FlowPointData({
    required this.label,
    required this.shortLabel,
    required this.dateSubtitle,
    required this.received,
    required this.sent,
    required this.inTransfers,
    required this.outTransfers,
    required this.transactions,
  });

  double get net => received - sent;
}

/// Model for a calendar month containing its weekly flow points
class MonthFlowData {
  final String monthName;
  final String shortName;
  final int monthIndex;
  final List<FlowPointData> weeks;

  const MonthFlowData({
    required this.monthName,
    required this.shortName,
    required this.monthIndex,
    required this.weeks,
  });
}

class AnalyticsTxItem {
  final String name;
  final String initial;
  final Color avatarBg;
  final String time;
  final double amount;
  final bool isReceived;
  final String status;

  const AnalyticsTxItem({
    required this.name,
    required this.initial,
    required this.avatarBg,
    required this.time,
    required this.amount,
    required this.isReceived,
    this.status = 'COMPLETED',
  });
}

class WebAnalyticsScreen extends StatefulWidget {
  final VoidCallback? onBack;

  const WebAnalyticsScreen({super.key, this.onBack});

  @override
  State<WebAnalyticsScreen> createState() => _WebAnalyticsScreenState();
}

class _WebAnalyticsScreenState extends State<WebAnalyticsScreen>
    with SingleTickerProviderStateMixin {
  bool _isMonthly = true;

  // Selected indices
  final int _selectedMonthOfYear = 9; // Defaults to October 2026 (Index 9 in Jan..Dec)
  int _selectedWeekIndex = 3; // Defaults to Week 4 (matches Page 11 design)
  int _selectedMonthIndex = 3; // Defaults to April in Yearly mode
  final int _selectedYear = 2026; // Defaults to 2026 (options: 2026, 2025)
  final int _selectedQuarterIndex = 0; // 0 = All (12M), 1 = Q1, 2 = Q2, 3 = Q3, 4 = Q4
  late AnimationController _animController;
  late Animation<double> _scrubAnimation;
  double _currentScrubFraction = 3.0; // Current floating position in index space

  // Catalog of all 12 Months in 2026 with weekly data
  late List<MonthFlowData> _monthsCatalog;

  // Yearly Datasets
  late List<FlowPointData> _yearlyMonths2026;
  late final List<FlowPointData> _yearlyMonths2025;
  bool _isLoading = false;
  String? _loadedAccountId;

  @override
  void initState() {
    super.initState();

    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 320),
    );

    _scrubAnimation = Tween<double>(begin: 3.0, end: 3.0).animate(
      CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic),
    )..addListener(() {
        setState(() {
          _currentScrubFraction = _scrubAnimation.value;
        });
      });

    _initDatasets();
    BankService().addListener(_onBankServiceUpdated);
    _loadOracleTransactions();
  }

  void _onBankServiceUpdated() {
    final currentAcc = _resolveAccountId();
    if (currentAcc != _loadedAccountId && mounted) {
      _loadOracleTransactions();
    }
  }

  void _buildEmpty2026Catalog() {
    const monthNames = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    const shortNames = [
      'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
    ];
    const monthDays = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

    _monthsCatalog = List.generate(12, (mIdx) {
      final mName = monthNames[mIdx];
      final sName = shortNames[mIdx];
      final lastDay = monthDays[mIdx];

      return MonthFlowData(
        monthName: '$mName 2026',
        shortName: sName,
        monthIndex: mIdx,
        weeks: [
          FlowPointData(
            label: 'Week 1',
            shortLabel: 'W1',
            dateSubtitle: '$sName 01 - $sName 07, 2026',
            received: 0.0,
            sent: 0.0,
            inTransfers: 0,
            outTransfers: 0,
            transactions: const [],
          ),
          FlowPointData(
            label: 'Week 2',
            shortLabel: 'W2',
            dateSubtitle: '$sName 08 - $sName 14, 2026',
            received: 0.0,
            sent: 0.0,
            inTransfers: 0,
            outTransfers: 0,
            transactions: const [],
          ),
          FlowPointData(
            label: 'Week 3',
            shortLabel: 'W3',
            dateSubtitle: '$sName 15 - $sName 21, 2026',
            received: 0.0,
            sent: 0.0,
            inTransfers: 0,
            outTransfers: 0,
            transactions: const [],
          ),
          FlowPointData(
            label: 'Week 4',
            shortLabel: 'W4',
            dateSubtitle: '$sName 22 - $sName ${lastDay.toString().padLeft(2, '0')}, 2026',
            received: 0.0,
            sent: 0.0,
            inTransfers: 0,
            outTransfers: 0,
            transactions: const [],
          ),
        ],
      );
    });

    _yearlyMonths2026 = List.generate(12, (mIdx) {
      final mName = monthNames[mIdx];
      final sName = shortNames[mIdx];
      final lastDay = monthDays[mIdx];
      return FlowPointData(
        label: mName,
        shortLabel: sName,
        dateSubtitle: '$sName 01 - $sName ${lastDay.toString().padLeft(2, '0')}, 2026',
        received: 0.0,
        sent: 0.0,
        inTransfers: 0,
        outTransfers: 0,
        transactions: const [],
      );
    });
  }

  String _resolveAccountId() {
    final bank = BankService();
    String acc = bank.activeAccountId;
    if (acc.isEmpty) {
      acc = DeviceStorage.getUserAccountId() ?? '';
    }
    final userEmail = (bank.user.email).toLowerCase();
    final userName = (bank.user.name).toLowerCase();
    final userId = AuthApiService().currentUserId ?? DeviceStorage.getUserId() ?? '';

    if (userId == 'U9001' || acc == '1000-9000-0001' || userEmail.contains('paolo') || userName.contains('paolo')) {
      return 'A9001';
    }
    if (userId == 'U9002' || acc == '1000-9000-0002' || userEmail.contains('elena') || userName.contains('elena')) {
      return 'A9002';
    }
    if (userId == 'U9003' || acc == '1000-9000-0003' || userEmail.contains('marco') || userName.contains('marco')) {
      return 'A9003';
    }
    if (acc.isNotEmpty) {
      if (acc == '1000-9000-0001') return 'A9001';
      if (acc == '1000-9000-0002') return 'A9002';
      if (acc == '1000-9000-0003') return 'A9003';
      return acc;
    }
    return 'A9001';
  }

  String _resolveCounterpartyName(String otherId, bool isIncoming) {
    switch (otherId) {
      case 'A9001':
      case '1000-9000-0001':
        return 'Paolo Reyes';
      case 'A9002':
      case '1000-9000-0002':
        return 'Elena Cruz';
      case 'A9003':
      case '1000-9000-0003':
        return 'Marco Santos';
      case 'A8001':
        return 'Enterprise Payroll';
      case 'A8002':
        return 'Merchant Settlement';
      case 'A8003':
        return 'Global Exchange';
      case 'A8004':
        return 'Utility & Bill Settlement';
      default:
        return isIncoming ? 'Counterparty Deposit' : 'Transfer Settlement';
    }
  }

  Color _getAvatarColor(String name) {
    if (name.contains('Paolo')) return const Color(0xFF2F78A8);
    if (name.contains('Elena')) return const Color(0xFF1C6E5A);
    if (name.contains('Marco')) return const Color(0xFF8B5CF6);
    if (name.contains('Payroll') || name.contains('Enterprise')) return const Color(0xFF2F78A8);
    if (name.contains('Utility') || name.contains('Bill')) return const Color(0xFFD9822B);
    if (name.contains('Merchant')) return const Color(0xFFE11D48);
    return const Color(0xFF4A5568);
  }

  String _formatTxnTime(DateTime dt) {
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    final m = months[dt.month - 1];
    final d = dt.day.toString().padLeft(2, '0');
    final hour12 = dt.hour % 12 == 0 ? 12 : dt.hour % 12;
    final minute = dt.minute.toString().padLeft(2, '0');
    final ampm = dt.hour >= 12 ? 'pm' : 'am';
    return '$m $d, $hour12:$minute $ampm';
  }

  Future<void> _loadOracleTransactions() async {
    final accountId = _resolveAccountId();
    _loadedAccountId = accountId;

    setState(() {
      _isLoading = true;
    });

    try {
      final token = AuthApiService().currentAccessToken ?? DeviceStorage.getAccessToken();
      final headers = <String, String>{
        'Accept': 'application/json',
        if (token != null && token.isNotEmpty) 'Authorization': 'Bearer $token',
      };

      final candidateBases = [
        BankService().baseUrl,
        'http://localhost:8080',
        'http://127.0.0.1:8080',
        'http://localhost:8082',
        'http://127.0.0.1:8082',
      ];

      List<dynamic> allTransactions = [];

      for (final base in candidateBases) {
        try {
          List<dynamic> fetched = [];
          int page = 0;
          bool hasMore = true;

          while (hasMore && page < 10) {
            final uri = Uri.parse('$base/api/v1/transfers/accounts/$accountId/transactions?page=$page&size=100');
            http.Response res;
            try {
              res = await http.get(uri, headers: headers).timeout(const Duration(seconds: 4));
            } catch (_) {
              res = await http.get(uri).timeout(const Duration(seconds: 4));
            }

            if (res.statusCode == 200) {
              final decoded = jsonDecode(res.body);
              if (decoded is List) {
                fetched.addAll(decoded);
                if (decoded.length < 100) {
                  hasMore = false;
                } else {
                  page++;
                }
              } else {
                hasMore = false;
              }
            } else {
              hasMore = false;
            }
          }

          if (fetched.isNotEmpty) {
            allTransactions = fetched;
            break;
          }
        } catch (_) {}
      }

      if (allTransactions.isNotEmpty) {
        _aggregateTransactionsIntoCatalog(accountId, allTransactions);
      }
    } catch (e) {
      debugPrint('[Analytics] Error loading Oracle transactions: $e');
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  void _aggregateTransactionsIntoCatalog(String accountId, List<dynamic> rawList) {
    const monthNames = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    const shortNames = [
      'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
    ];
    const monthDays = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

    final weekReceived = List.generate(12, (_) => List.filled(4, 0.0));
    final weekSent = List.generate(12, (_) => List.filled(4, 0.0));
    final weekInCount = List.generate(12, (_) => List.filled(4, 0));
    final weekOutCount = List.generate(12, (_) => List.filled(4, 0));
    final weekTxns = List.generate(12, (_) => List.generate(4, (_) => <AnalyticsTxItem>[]));

    for (final item in rawList) {
      if (item is! Map) continue;
      final createdAtStr = (item['created_at'] ?? item['createdAt'])?.toString();
      if (createdAtStr == null) continue;

      DateTime? dt = DateTime.tryParse(createdAtStr);
      if (dt == null) continue;

      if (dt.year != 2026) continue;
      final mIdx = dt.month - 1;
      if (mIdx < 0 || mIdx > 11) continue;

      final day = dt.day;
      int wIdx;
      if (day <= 7) {
        wIdx = 0;
      } else if (day <= 14) {
        wIdx = 1;
      } else if (day <= 21) {
        wIdx = 2;
      } else {
        wIdx = 3;
      }

      final amount = ((item['amount'] ?? 0) as num).toDouble();
      final src = (item['source_account_id'] ?? item['from_account_id'] ?? item['fromAccountId'])?.toString() ?? '';
      final tgt = (item['target_account_id'] ?? item['to_account_id'] ?? item['toAccountId'])?.toString() ?? '';

      final isIncoming = tgt == accountId ||
          (accountId == 'A9001' && tgt == '1000-9000-0001') ||
          (accountId == 'A9002' && tgt == '1000-9000-0002') ||
          (accountId == 'A9003' && tgt == '1000-9000-0003');

      final isOutgoing = src == accountId ||
          (accountId == 'A9001' && src == '1000-9000-0001') ||
          (accountId == 'A9002' && src == '1000-9000-0002') ||
          (accountId == 'A9003' && src == '1000-9000-0003');

      final otherAccId = isIncoming ? src : tgt;
      final memo = item['memo']?.toString();
      final counterpartyName = (memo != null && memo.isNotEmpty)
          ? memo
          : _resolveCounterpartyName(otherAccId, isIncoming);

      final txItem = AnalyticsTxItem(
        name: counterpartyName,
        initial: counterpartyName.isNotEmpty ? counterpartyName[0].toUpperCase() : 'T',
        avatarBg: _getAvatarColor(counterpartyName),
        time: _formatTxnTime(dt),
        amount: amount,
        isReceived: isIncoming,
        status: isIncoming ? 'RECEIVED' : 'COMPLETED',
      );

      if (isIncoming) {
        weekReceived[mIdx][wIdx] += amount;
        weekInCount[mIdx][wIdx] += 1;
        weekTxns[mIdx][wIdx].add(txItem);
      } else if (isOutgoing) {
        weekSent[mIdx][wIdx] += amount;
        weekOutCount[mIdx][wIdx] += 1;
        weekTxns[mIdx][wIdx].add(txItem);
      }
    }

    _monthsCatalog = List.generate(12, (mIdx) {
      final mName = monthNames[mIdx];
      final sName = shortNames[mIdx];
      final lastDay = monthDays[mIdx];

      return MonthFlowData(
        monthName: '$mName 2026',
        shortName: sName,
        monthIndex: mIdx,
        weeks: [
          FlowPointData(
            label: 'Week 1',
            shortLabel: 'W1',
            dateSubtitle: '$sName 01 - $sName 07, 2026',
            received: weekReceived[mIdx][0],
            sent: weekSent[mIdx][0],
            inTransfers: weekInCount[mIdx][0],
            outTransfers: weekOutCount[mIdx][0],
            transactions: weekTxns[mIdx][0],
          ),
          FlowPointData(
            label: 'Week 2',
            shortLabel: 'W2',
            dateSubtitle: '$sName 08 - $sName 14, 2026',
            received: weekReceived[mIdx][1],
            sent: weekSent[mIdx][1],
            inTransfers: weekInCount[mIdx][1],
            outTransfers: weekOutCount[mIdx][1],
            transactions: weekTxns[mIdx][1],
          ),
          FlowPointData(
            label: 'Week 3',
            shortLabel: 'W3',
            dateSubtitle: '$sName 15 - $sName 21, 2026',
            received: weekReceived[mIdx][2],
            sent: weekSent[mIdx][2],
            inTransfers: weekInCount[mIdx][2],
            outTransfers: weekOutCount[mIdx][2],
            transactions: weekTxns[mIdx][2],
          ),
          FlowPointData(
            label: 'Week 4',
            shortLabel: 'W4',
            dateSubtitle: '$sName 22 - $sName ${lastDay.toString().padLeft(2, '0')}, 2026',
            received: weekReceived[mIdx][3],
            sent: weekSent[mIdx][3],
            inTransfers: weekInCount[mIdx][3],
            outTransfers: weekOutCount[mIdx][3],
            transactions: weekTxns[mIdx][3],
          ),
        ],
      );
    });

    _yearlyMonths2026 = List.generate(12, (mIdx) {
      final mName = monthNames[mIdx];
      final sName = shortNames[mIdx];
      final lastDay = monthDays[mIdx];

      double mRec = 0;
      double mSent = 0;
      int mInCount = 0;
      int mOutCount = 0;
      final List<AnalyticsTxItem> mAllTxns = [];

      for (int w = 0; w < 4; w++) {
        mRec += weekReceived[mIdx][w];
        mSent += weekSent[mIdx][w];
        mInCount += weekInCount[mIdx][w];
        mOutCount += weekOutCount[mIdx][w];
        mAllTxns.addAll(weekTxns[mIdx][w]);
      }

      return FlowPointData(
        label: mName,
        shortLabel: sName,
        dateSubtitle: '$sName 01 - $sName ${lastDay.toString().padLeft(2, '0')}, 2026',
        received: mRec,
        sent: mSent,
        inTransfers: mInCount,
        outTransfers: mOutCount,
        transactions: mAllTxns,
      );
    });
  }
  void _initDatasets() {
    _buildEmpty2026Catalog();

    // Yearly Dataset (12 Months in 2025)
    _yearlyMonths2025 = [
      FlowPointData(
        label: 'January',
        shortLabel: 'Jan',
        dateSubtitle: 'Jan 01 - Jan 31, 2025',
        received: 12000.0,
        sent: 38000.0,
        inTransfers: 2,
        outTransfers: 12,
        transactions: const [],
      ),
      FlowPointData(
        label: 'February',
        shortLabel: 'Feb',
        dateSubtitle: 'Feb 01 - Feb 28, 2025',
        received: 20000.0,
        sent: 32000.0,
        inTransfers: 3,
        outTransfers: 11,
        transactions: const [],
      ),
      FlowPointData(
        label: 'March',
        shortLabel: 'Mar',
        dateSubtitle: 'Mar 01 - Mar 31, 2025',
        received: 9000.0,
        sent: 36000.0,
        inTransfers: 2,
        outTransfers: 14,
        transactions: const [],
      ),
      FlowPointData(
        label: 'April',
        shortLabel: 'Apr',
        dateSubtitle: 'Apr 01 - Apr 30, 2025',
        received: 52000.0,
        sent: 14000.0,
        inTransfers: 4,
        outTransfers: 10,
        transactions: const [],
      ),
      FlowPointData(
        label: 'May',
        shortLabel: 'May',
        dateSubtitle: 'May 01 - May 31, 2025',
        received: 18000.0,
        sent: 30000.0,
        inTransfers: 3,
        outTransfers: 12,
        transactions: const [],
      ),
      FlowPointData(
        label: 'June',
        shortLabel: 'Jun',
        dateSubtitle: 'Jun 01 - Jun 30, 2025',
        received: 13000.0,
        sent: 42000.0,
        inTransfers: 2,
        outTransfers: 8,
        transactions: const [],
      ),
      FlowPointData(
        label: 'July',
        shortLabel: 'Jul',
        dateSubtitle: 'Jul 01 - Jul 31, 2025',
        received: 21000.0,
        sent: 35000.0,
        inTransfers: 4,
        outTransfers: 18,
        transactions: const [],
      ),
      FlowPointData(
        label: 'August',
        shortLabel: 'Aug',
        dateSubtitle: 'Aug 01 - Aug 31, 2025',
        received: 11500.0,
        sent: 32000.0,
        inTransfers: 3,
        outTransfers: 15,
        transactions: const [],
      ),
      FlowPointData(
        label: 'September',
        shortLabel: 'Sep',
        dateSubtitle: 'Sep 01 - Sep 30, 2025',
        received: 15000.0,
        sent: 39000.0,
        inTransfers: 3,
        outTransfers: 14,
        transactions: const [],
      ),
      FlowPointData(
        label: 'October',
        shortLabel: 'Oct',
        dateSubtitle: 'Oct 01 - Oct 31, 2025',
        received: 45000.0,
        sent: 85000.0,
        inTransfers: 3,
        outTransfers: 15,
        transactions: const [],
      ),
      FlowPointData(
        label: 'November',
        shortLabel: 'Nov',
        dateSubtitle: 'Nov 01 - Nov 30, 2025',
        received: 16000.0,
        sent: 40000.0,
        inTransfers: 2,
        outTransfers: 14,
        transactions: const [],
      ),
      FlowPointData(
        label: 'December',
        shortLabel: 'Dec',
        dateSubtitle: 'Dec 01 - Dec 31, 2025',
        received: 25000.0,
        sent: 52000.0,
        inTransfers: 3,
        outTransfers: 18,
        transactions: const [],
      ),
    ];
  }

  @override
  void dispose() {
    BankService().removeListener(_onBankServiceUpdated);
    _animController.dispose();
    super.dispose();
  }

  void _animateToTarget(double targetIndex) {
    _animController.stop();
    _scrubAnimation = Tween<double>(
      begin: _currentScrubFraction,
      end: targetIndex,
    ).animate(
      CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic),
    );
    _animController.forward(from: 0.0);
  }

  void _onSelectWeek(int index) {
    setState(() {
      _selectedWeekIndex = index;
    });
    _animateToTarget(index.toDouble());
  }

  void _onSelectMonth(int index) {
    setState(() {
      _selectedMonthIndex = index;
    });
    _animateToTarget(index.toDouble());
  }

  void _onToggleMode(bool monthly) {
    if (_isMonthly == monthly) return;
    setState(() {
      _isMonthly = monthly;
      final maxLen = (monthly ? _currentMonthlyWeeks : _currentYearlyFilteredMonths).length;
      final activeIdx = monthly
          ? _selectedWeekIndex.clamp(0, maxLen - 1)
          : _selectedMonthIndex.clamp(0, maxLen - 1);
      _currentScrubFraction = activeIdx.toDouble();
    });
    _animateToTarget(
      (monthly ? _selectedWeekIndex : _selectedMonthIndex).toDouble(),
    );
  }

  List<FlowPointData> get _currentMonthlyWeeks =>
      _monthsCatalog[_selectedMonthOfYear].weeks;

  List<FlowPointData> get _currentFullYearMonths =>
      _selectedYear == 2026 ? _yearlyMonths2026 : _yearlyMonths2025;

  List<FlowPointData> get _currentYearlyFilteredMonths {
    final full = _currentFullYearMonths;
    switch (_selectedQuarterIndex) {
      case 1:
        return full.sublist(0, 3);
      case 2:
        return full.sublist(3, 6);
      case 3:
        return full.sublist(6, 9);
      case 4:
        return full.sublist(9, 12);
      default:
        return full.length > 5 ? full.sublist(0, 5) : full;
    }
  }

  List<FlowPointData> get _currentDataset =>
      _isMonthly ? _currentMonthlyWeeks : _currentYearlyFilteredMonths;



  // Grand totals
  double get _totalSentMonth {
    double sum = 0;
    for (final w in _currentMonthlyWeeks) {
      sum += w.sent;
    }
    return sum;
  }

  double get _totalReceivedMonth {
    double sum = 0;
    for (final w in _currentMonthlyWeeks) {
      sum += w.received;
    }
    return sum;
  }

  int get _transfersOutMonth {
    int sum = 0;
    for (final w in _currentMonthlyWeeks) {
      sum += w.outTransfers;
    }
    return sum;
  }

  int get _transfersInMonth {
    int sum = 0;
    for (final w in _currentMonthlyWeeks) {
      sum += w.inTransfers;
    }
    return sum;
  }





  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF7F7F7),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 28.0, vertical: 24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_isLoading) ...[
                const LinearProgressIndicator(minHeight: 2, backgroundColor: Colors.transparent, color: Color(0xFF6B46C1)),
                const SizedBox(height: 8),
              ],
              // 1. Executive Module Header (Replaces redundant underlined title)
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      if (widget.onBack != null) ...[
                        IconButton(
                          icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 18),
                          onPressed: widget.onBack,
                          padding: EdgeInsets.zero,
                          constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                        ),
                        const SizedBox(width: 8),
                      ],
                      const AuraLogo(
                        size: 38,
                        style: AuraLogoStyle.violet,
                        borderRadius: 10,
                      ),
                      const SizedBox(width: 14),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Container(
                                width: 8,
                                height: 8,
                                decoration: const BoxDecoration(
                                  color: Color(0xFF2FA37E),
                                  shape: BoxShape.circle,
                                ),
                              ),
                              const SizedBox(width: 6),
                              const Text(
                                'FINANCIAL INTELLIGENCE',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w800,
                                  color: Color(0xFF7D8892),
                                  letterSpacing: 0.8,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          const Text(
                            'Analytics & Flow',
                            style: TextStyle(
                              fontSize: 26,
                              fontWeight: FontWeight.w800,
                              color: Color(0xFF10171C),
                              letterSpacing: -0.6,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),

                  // Quick Action Toolbar: Monthly / Yearly Pill Toggle
                  Container(
                    padding: const EdgeInsets.all(4),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(30),
                      border: Border.all(color: const Color(0xFFEAECEE)),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withValues(alpha: 0.04),
                          blurRadius: 8,
                          offset: const Offset(0, 2),
                        ),
                      ],
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _buildModeTab('Monthly', _isMonthly, () => _onToggleMode(true)),
                        _buildModeTab('Yearly', !_isMonthly, () => _onToggleMode(false)),
                      ],
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 24),

              // 2. Executive 3-Card KPI Bento (Outflow, Inflow, Net Cash Position)
              _buildDynamicKpiCards(),

              const SizedBox(height: 24),

              // 3. Interactive Transfer Flow Chart with Institutional Telemetry
              _buildInteractiveTransferFlowSection(),

              const SizedBox(height: 24),

              // 4. Ledger History Section (Monthly History vs Yearly Summaries)
              if (_isMonthly) ...[
                _buildMonthlyHistorySection(),
              ] else ...[
                _buildYearlySummariesSection(),
              ],

              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildModeTab(String label, bool isActive, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
        decoration: BoxDecoration(
          color: isActive ? const Color(0xFF10171C) : Colors.transparent,
          borderRadius: BorderRadius.circular(24),
          boxShadow: isActive
              ? [
                  BoxShadow(
                    color: const Color(0xFF10171C).withValues(alpha: 0.3),
                    blurRadius: 8,
                    offset: const Offset(0, 2),
                  ),
                ]
              : null,
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 12.5,
            fontWeight: isActive ? FontWeight.w700 : FontWeight.w600,
            color: isActive ? Colors.white : const Color(0xFF7D8892),
          ),
        ),
      ),
    );
  }





  Widget _buildDynamicKpiCards() {
    final sentTitle = _isMonthly ? 'Total Sent' : 'Total Sent (2026)';
    final sentAmount = _isMonthly ? _totalSentMonth : 508000.00;
    final sentCount = _isMonthly ? '$_transfersOutMonth Transfers Out' : '185 Transfers Out';

    final receivedTitle = _isMonthly ? 'Total Received' : 'Total Received (2026)';
    final receivedAmount = _isMonthly ? _totalReceivedMonth : 160000.00;
    final receivedCount = _isMonthly ? '$_transfersInMonth Transfers In' : '43 Transfers In';

    final netDiff = receivedAmount - sentAmount;

    return LayoutBuilder(
      builder: (context, constraints) {
        final isWide = constraints.maxWidth >= 720;
        final children = [
          // 1. Dark Purple/Violet Gradient Card: Total Sent
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFF1A2329), Color(0xFF10171C)],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: Colors.white.withValues(alpha: 0.12), width: 1.0),
              boxShadow: [
                BoxShadow(
                  color: const Color(0xFF10171C).withValues(alpha: 0.22),
                  blurRadius: 18,
                  offset: const Offset(0, 6),
                ),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      sentTitle,
                      style: const TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFFD8B4FE),
                      ),
                    ),
                    Container(
                      width: 28,
                      height: 28,
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.12),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Icon(Icons.arrow_outward_rounded, size: 16, color: Colors.white),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                Text(
                  'PHP ${_formatCurrency(sentAmount)}',
                  style: const TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    letterSpacing: -0.6,
                  ),
                ),
                const SizedBox(height: 20),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      sentCount,
                      style: const TextStyle(
                        fontSize: 12,
                        fontStyle: FontStyle.italic,
                        color: Color(0xFFC084FC),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    Container(
                      width: 22,
                      height: 22,
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.12),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(Icons.arrow_downward_rounded, size: 13, color: Colors.white),
                    ),
                  ],
                ),
              ],
            ),
          ),

          if (isWide) const SizedBox(width: 20) else const SizedBox(height: 16),

          // 2. White Card with Green Accent: Total Received
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: const Color(0xFFEAECEE)),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.03),
                  blurRadius: 16,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      receivedTitle,
                      style: const TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFF1C6E5A),
                      ),
                    ),
                    Container(
                      width: 28,
                      height: 28,
                      decoration: BoxDecoration(
                        color: const Color(0xFFE4F5EE),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Icon(Icons.south_west_rounded, size: 15, color: Color(0xFF17805F)),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                Text(
                  'PHP ${_formatCurrency(receivedAmount)}',
                  style: const TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.w800,
                    color: Color(0xFF10171C),
                    letterSpacing: -0.6,
                  ),
                ),
                const SizedBox(height: 20),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      receivedCount,
                      style: const TextStyle(
                        fontSize: 12,
                        fontStyle: FontStyle.italic,
                        color: Color(0xFF7D8892),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    Container(
                      width: 22,
                      height: 22,
                      decoration: const BoxDecoration(
                        color: Color(0xFFF2FAF6),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(Icons.arrow_upward_rounded, size: 13, color: Color(0xFF2F78A8)),
                    ),
                  ],
                ),
              ],
            ),
          ),

          if (isWide) const SizedBox(width: 20) else const SizedBox(height: 16),

          // 3. White Card: Net Volume Difference
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: const Color(0xFFEAECEE)),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.03),
                  blurRadius: 16,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'Net Volume Difference',
                      style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFF47525C),
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                      decoration: BoxDecoration(
                        color: const Color(0xFFE4F5EE),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.trending_up_rounded, size: 13, color: Color(0xFF17805F)),
                          SizedBox(width: 3),
                          Text(
                            '+14.2% YoY',
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.w800,
                              color: Color(0xFF15803D),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                Text(
                  '${netDiff >= 0 ? '+' : '-'} PHP ${_formatCurrency(netDiff.abs())}',
                  style: const TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.w800,
                    color: Color(0xFF10171C),
                    letterSpacing: -0.6,
                  ),
                ),
                const SizedBox(height: 20),
                const Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Expenditure Ratio',
                      style: TextStyle(
                        fontSize: 12,
                        color: Color(0xFF7D8892),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    Text(
                      '76% of Outflow',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: Color(0xFF26313A),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ];

        if (isWide) {
          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: children.map((c) => c is SizedBox ? c : Expanded(child: c)).toList(),
          );
        }
        return Column(children: children);
      },
    );
  }

  Widget _buildInteractiveTransferFlowSection() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFFEAECEE)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.03),
            blurRadius: 16,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header with Legend & Telemetry Status
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Text(
                    'Transfer Flow',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      color: Color(0xFF10171C),
                      letterSpacing: -0.4,
                    ),
                  ),
                  SizedBox(height: 3),
                  Text(
                    'Smooth comparison of outgoing versus incoming capital',
                    style: TextStyle(
                      fontSize: 12.5,
                      color: Color(0xFF7D8892),
                      fontWeight: FontWeight.w400,
                    ),
                  ),
                ],
              ),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildLegendDot(const Color(0xFF2FA37E), 'Received'),
                  const SizedBox(width: 16),
                  _buildLegendDot(const Color(0xFF10171C), 'Sent'),
                ],
              ),
            ],
          ),

          const SizedBox(height: 20),

          // Interactive Movable Chart Canvas with GestureDetector
          LayoutBuilder(
            builder: (context, constraints) {
              final chartWidth = constraints.maxWidth;
              const chartHeight = 200.0;
              final dataset = _currentDataset;

              return GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTapDown: (details) {
                  _handleScrubGesture(details.localPosition.dx, chartWidth, dataset.length);
                },
                onTapUp: (details) {
                  _handleScrubGesture(details.localPosition.dx, chartWidth, dataset.length);
                  final targetIndex = _currentScrubFraction.round().clamp(0, dataset.length - 1);
                  if (_isMonthly) {
                    _onSelectWeek(targetIndex);
                  } else {
                    _onSelectMonth(targetIndex);
                  }
                },
                onHorizontalDragUpdate: (details) {
                  _handleScrubGesture(details.localPosition.dx, chartWidth, dataset.length);
                },
                onHorizontalDragEnd: (details) {
                  final targetIndex = _currentScrubFraction.round().clamp(0, dataset.length - 1);
                  if (_isMonthly) {
                    _onSelectWeek(targetIndex);
                  } else {
                    _onSelectMonth(targetIndex);
                  }
                },
                child: MouseRegion(
                  cursor: SystemMouseCursors.click,
                  child: SizedBox(
                    height: chartHeight,
                    width: chartWidth,
                    child: CustomPaint(
                      painter: _PointedTransferFlowPainter(
                        dataset: dataset,
                        scrubFraction: _currentScrubFraction,
                        isMonthly: _isMonthly,
                      ),
                    ),
                  ),
                ),
              );
            },
          ),

          const SizedBox(height: 16),

          // Clickable Week / Month Pills Bar with Apple-like Container
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F7F7),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFFEAECEE)),
            ),
            child: _isMonthly ? _buildWeekClickablePills() : _buildMonthClickablePills(),
          ),
        ],
      ),
    );
  }

  void _handleScrubGesture(double localX, double totalWidth, int count) {
    const double paddingX = 24.0;
    final usableWidth = totalWidth - (2 * paddingX);
    if (usableWidth <= 0 || count <= 1) return;

    final normalized = (localX - paddingX) / usableWidth;
    final indexFraction = (normalized * (count - 1)).clamp(0.0, (count - 1).toDouble());

    setState(() {
      _currentScrubFraction = indexFraction;
      final nearest = indexFraction.round().clamp(0, count - 1);
      if (_isMonthly) {
        _selectedWeekIndex = nearest;
      } else {
        _selectedMonthIndex = nearest;
      }
    });
  }

  Widget _buildWeekClickablePills() {
    final weeks = _currentMonthlyWeeks;
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: List.generate(weeks.length, (index) {
        final isSelected = _selectedWeekIndex == index;
        return GestureDetector(
          onTap: () => _onSelectWeek(index),
          behavior: HitTestBehavior.opaque,
          child: MouseRegion(
            cursor: SystemMouseCursors.click,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              padding: const EdgeInsets.symmetric(horizontal: 14.0, vertical: 6.0),
              decoration: BoxDecoration(
                color: isSelected ? Colors.white : Colors.transparent,
                borderRadius: BorderRadius.circular(8),
                border: isSelected ? Border.all(color: const Color(0xFFEAECEE)) : null,
                boxShadow: isSelected
                    ? [
                        BoxShadow(
                          color: Colors.black.withValues(alpha: 0.05),
                          blurRadius: 4,
                          offset: const Offset(0, 1),
                        ),
                      ]
                    : null,
              ),
              child: Text(
                'Week ${index + 1}',
                style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: isSelected ? FontWeight.w800 : FontWeight.w500,
                  color: isSelected ? const Color(0xFF10171C) : const Color(0xFF7D8892),
                ),
              ),
            ),
          ),
        );
      }),
    );
  }

  Widget _buildMonthClickablePills() {
    const months = ['January', 'February', 'March', 'April', 'May'];
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: months.asMap().entries.map((entry) {
        final index = entry.key;
        final m = entry.value;
        final isSelected = _selectedMonthIndex == index;
        return GestureDetector(
          onTap: () => _onSelectMonth(index),
          behavior: HitTestBehavior.opaque,
          child: MouseRegion(
            cursor: SystemMouseCursors.click,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              padding: const EdgeInsets.symmetric(horizontal: 14.0, vertical: 6.0),
              decoration: BoxDecoration(
                color: isSelected ? Colors.white : Colors.transparent,
                borderRadius: BorderRadius.circular(8),
                border: isSelected ? Border.all(color: const Color(0xFFEAECEE)) : null,
                boxShadow: isSelected
                    ? [
                        BoxShadow(
                          color: Colors.black.withValues(alpha: 0.05),
                          blurRadius: 4,
                          offset: const Offset(0, 1),
                        ),
                      ]
                    : null,
              ),
              child: Text(
                m,
                style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: isSelected ? FontWeight.w800 : FontWeight.w500,
                  color: isSelected ? const Color(0xFF10171C) : const Color(0xFF7D8892),
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _buildLegendDot(Color color, String label) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 5),
        Text(
          label,
          style: const TextStyle(fontSize: 11.5, color: Color(0xFF47525C), fontWeight: FontWeight.w600),
        ),
      ],
    );
  }

  Widget _buildMonthlyHistorySection() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFFEAECEE)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.03),
            blurRadius: 16,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Section Header Row
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Text(
                    'Settlement History Ledger',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w800,
                      color: Color(0xFF10171C),
                      letterSpacing: -0.3,
                    ),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'Transfers settled & cleared in October 2026',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                      color: Color(0xFF7D8892),
                    ),
                  ),
                ],
              ),
              // High-End "View Statement" Button
              TextButton.icon(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (context) => const WebStatementScreen(),
                    ),
                  );
                },
                icon: const Icon(Icons.receipt_long_rounded, size: 15, color: Color(0xFF10171C)),
                label: const Text(
                  'View Statement',
                  style: TextStyle(
                    color: Color(0xFF10171C),
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                style: TextButton.styleFrom(
                  backgroundColor: const Color(0xFFF2FAF6),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                    side: const BorderSide(color: Color(0xFFC9EBDD)),
                  ),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                ),
              ),
            ],
          ),

          const SizedBox(height: 18),

          // Date Group 1: October 14, 2026
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F7F7),
              borderRadius: BorderRadius.circular(6),
            ),
            child: const Text(
              'Wednesday, October 14, 2026',
              style: TextStyle(
                fontSize: 11,
                color: Color(0xFF47525C),
                fontWeight: FontWeight.w700,
                letterSpacing: 0.2,
              ),
            ),
          ),
          const SizedBox(height: 10),
          _buildTransactionItem(
            avatarBg: const Color(0xFF220055),
            initial: 'D',
            name: 'Drake Montefalco',
            category: 'Intra-Bank Direct Transfer',
            time: 'Oct 14, 2:45 PM',
            amount: '- PHP 2,500.00',
            isPositive: false,
            status: 'SETTLED',
            statusColor: const Color(0xFF17805F),
            statusBg: const Color(0xFFE4F5EE),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Divider(height: 1, color: Color(0xFFF1F3F4)),
          ),
          _buildTransactionItem(
            avatarBg: const Color(0xFF2F78A8),
            initial: 'K',
            name: 'Klare Riego',
            category: 'CBS Wire Inward Remittance',
            time: 'Oct 14, 1:45 PM',
            amount: '+ PHP 26,500.00',
            isPositive: true,
            status: 'RECEIVED',
            statusColor: const Color(0xFF17805F),
            statusBg: const Color(0xFFE4F5EE),
          ),

          const SizedBox(height: 18),

          // Date Group 2: October 03, 2026
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F7F7),
              borderRadius: BorderRadius.circular(6),
            ),
            child: const Text(
              'Friday, October 03, 2026',
              style: TextStyle(
                fontSize: 11,
                color: Color(0xFF47525C),
                fontWeight: FontWeight.w700,
                letterSpacing: 0.2,
              ),
            ),
          ),
          const SizedBox(height: 10),
          _buildTransactionItem(
            avatarBg: const Color(0xFF1C6E5A),
            initial: 'A',
            name: 'Angel Lou',
            category: 'Settlement Disbursement',
            time: 'Oct 03, 11:20 AM',
            amount: '- PHP 2,500.00',
            isPositive: false,
            status: 'SETTLED',
            statusColor: const Color(0xFF17805F),
            statusBg: const Color(0xFFE4F5EE),
          ),
        ],
      ),
    );
  }

  Widget _buildYearlySummariesSection() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFFEAECEE)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.03),
            blurRadius: 16,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Text(
                    'Monthly Summaries',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      color: Color(0xFF26313A),
                      letterSpacing: -0.4,
                    ),
                  ),
                  SizedBox(height: 3),
                  Text(
                    'Quarterly disbursement (2026)',
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w500,
                      color: Color(0xFF1C6E5A),
                    ),
                  ),
                ],
              ),
              ElevatedButton.icon(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (context) => const WebAnnualReportScreen(),
                    ),
                  );
                },
                icon: const Icon(Icons.description_outlined, size: 15, color: Colors.white),
                label: const Text(
                  'Annual Report',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF2D1052),
                  foregroundColor: Colors.white,
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                ),
              ),
            ],
          ),

          const SizedBox(height: 18),

          // Quarter 3
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F7F7),
              borderRadius: BorderRadius.circular(6),
            ),
            child: const Text(
              'Quarter 3 (Q3 2026)',
              style: TextStyle(
                fontSize: 11,
                color: Color(0xFF47525C),
                fontWeight: FontWeight.w700,
                letterSpacing: 0.2,
              ),
            ),
          ),
          const SizedBox(height: 10),
          _buildQuarterRow(
            initial: 'Jul',
            monthTitle: 'July 2026',
            transfersText: '24 transfers • ',
            greenAmount: '+ 55,000.00',
            gross: 'PHP 42,000.00',
            net: '+ PHP 26.5k net',
            avatarBg: const Color(0xFF10171C),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Divider(height: 1, color: Color(0xFFF1F3F4)),
          ),
          _buildQuarterRow(
            initial: 'Aug',
            monthTitle: 'August 2026',
            transfersText: '19 Transfers • ',
            greenAmount: '+ 39,000.00',
            gross: 'PHP 38,200.00',
            net: '+ PHP 13.8k net',
            avatarBg: const Color(0xFF1C6E5A),
          ),

          const SizedBox(height: 18),

          // Quarter 2
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F7F7),
              borderRadius: BorderRadius.circular(6),
            ),
            child: const Text(
              'Quarter 2 (Q2 2026)',
              style: TextStyle(
                fontSize: 11,
                color: Color(0xFF47525C),
                fontWeight: FontWeight.w700,
                letterSpacing: 0.2,
              ),
            ),
          ),
          const SizedBox(height: 10),
          _buildQuarterRow(
            initial: 'Jun',
            monthTitle: 'June 2026',
            transfersText: '10 Transfers • ',
            greenAmount: '+ 32,000.00',
            gross: 'PHP 50,000.00',
            net: '+ PHP 15.2k net',
            avatarBg: const Color(0xFF2F78A8),
          ),
        ],
      ),
    );
  }

  Widget _buildQuarterRow({
    required String initial,
    required String monthTitle,
    required String transfersText,
    required String greenAmount,
    required String gross,
    required String net,
    required Color avatarBg,
  }) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Expanded(
          child: Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: avatarBg,
                  borderRadius: BorderRadius.circular(8),
                ),
                alignment: Alignment.center,
                child: Text(
                  initial,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w700,
                    fontSize: 12,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      monthTitle,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 13.5,
                        color: Color(0xFF10171C),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        Text(
                          transfersText,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11, color: Color(0xFF7D8892)),
                        ),
                        Text(
                          greenAmount,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                            color: Color(0xFF17805F),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              gross,
              style: const TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 13,
                color: Color(0xFF10171C),
              ),
            ),
            const SizedBox(height: 2),
            Text(
              net,
              style: const TextStyle(
                fontSize: 11,
                color: Color(0xFF17805F),
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildTransactionItem({
    required Color avatarBg,
    required String initial,
    required String name,
    required String category,
    required String time,
    required String amount,
    required bool isPositive,
    required String status,
    required Color statusColor,
    required Color statusBg,
  }) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Expanded(
          child: Row(
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: avatarBg,
                  borderRadius: BorderRadius.circular(10),
                ),
                alignment: Alignment.center,
                child: Text(
                  initial,
                  style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 14),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 13.5,
                        color: Color(0xFF10171C),
                      ),
                    ),
                    const SizedBox(height: 1),
                    Text(
                      '$category • $time',
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11, color: Color(0xFF7D8892)),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              amount,
              style: TextStyle(
                fontWeight: FontWeight.w800,
                fontSize: 13.5,
                color: isPositive ? const Color(0xFF17805F) : const Color(0xFF10171C),
                letterSpacing: -0.2,
              ),
            ),
            const SizedBox(height: 3),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: statusBg,
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(
                status,
                style: TextStyle(
                  fontWeight: FontWeight.w800,
                  fontSize: 9.5,
                  color: statusColor,
                  letterSpacing: 0.3,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  String _formatCurrency(double val) {
    final parts = val.toStringAsFixed(2).split('.');
    final integerPart = parts[0].replaceAllMapped(
      RegExp(r'(\d{1,3})(?=(\d{3})+(?!\d))'),
      (Match m) => '${m[1]},',
    );
    return '$integerPart.${parts[1]}';
  }
}

/// Custom painter for pointed transfer flow with pointed callout badges,
/// node vertices, vertical guideline scrubber, and smooth gradients.
class _PointedTransferFlowPainter extends CustomPainter {
  final List<FlowPointData> dataset;
  final double scrubFraction;
  final bool isMonthly;

  _PointedTransferFlowPainter({
    required this.dataset,
    required this.scrubFraction,
    required this.isMonthly,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final h = size.height;
    if (dataset.isEmpty || w <= 0 || h <= 0) return;

    const double paddingX = 24.0;
    const double paddingTop = 38.0; // Space for pointed callout badge above
    const double paddingBottom = 38.0; // Space for pointed callout badge below
    final usableW = w - (2 * paddingX);
    final usableH = h - paddingTop - paddingBottom;

    // Baseline axis
    final axisPaint = Paint()
      ..color = const Color(0xFFF1F3F4)
      ..strokeWidth = 1.0;
    canvas.drawLine(Offset(0, h - 8), Offset(w, h - 8), axisPaint);

    // Subtle horizontal grid guides
    final gridPaint = Paint()
      ..color = const Color(0xFFF7F7F7)
      ..strokeWidth = 1.0;
    canvas.drawLine(Offset(paddingX, paddingTop + usableH * 0.33), Offset(w - paddingX, paddingTop + usableH * 0.33), gridPaint);
    canvas.drawLine(Offset(paddingX, paddingTop + usableH * 0.66), Offset(w - paddingX, paddingTop + usableH * 0.66), gridPaint);

    final n = dataset.length;
    final xStep = n > 1 ? usableW / (n - 1) : 0.0;

    // Determine max value for normalization
    double maxVal = 10000.0;
    for (final p in dataset) {
      maxVal = math.max(maxVal, math.max(p.received, p.sent));
    }
    maxVal *= 1.25; // 25% overhead for visual breathing room

    // Compute coordinate points for Received (In, Green) and Sent (Out, Violet)
    // Exactly n points: weeks in Monthly mode, 12 months in Yearly mode.
    final greenPoints = <Offset>[];
    final violetPoints = <Offset>[];

    for (int i = 0; i < n; i++) {
      final x = paddingX + (i * xStep);
      final greenNorm = (dataset[i].received / maxVal).clamp(0.05, 0.95);
      final violetNorm = (dataset[i].sent / maxVal).clamp(0.05, 0.95);

      final yGreen = (paddingTop + usableH) - (greenNorm * usableH);
      final yViolet = (paddingTop + usableH) - (violetNorm * usableH);

      greenPoints.add(Offset(x, yGreen));
      violetPoints.add(Offset(x, yViolet));
    }

    // Build smooth spline across points
    final greenPath = _buildPath(greenPoints);
    final violetPath = _buildPath(violetPoints);

    // 1. Draw subtle area gradients under curves
    final greenAreaPath = Path.from(greenPath)
      ..lineTo(greenPoints.last.dx, h - 8)
      ..lineTo(greenPoints.first.dx, h - 8)
      ..close();
    final greenShader = LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [
        const Color(0xFF2FA37E).withValues(alpha: 0.14),
        const Color(0xFF2FA37E).withValues(alpha: 0.0),
      ],
    ).createShader(Rect.fromLTWH(0, paddingTop, w, usableH));
    canvas.drawPath(greenAreaPath, Paint()..shader = greenShader);

    final violetAreaPath = Path.from(violetPath)
      ..lineTo(violetPoints.last.dx, h - 8)
      ..lineTo(violetPoints.first.dx, h - 8)
      ..close();
    final violetShader = LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [
        const Color(0xFF2F78A8).withValues(alpha: 0.12),
        const Color(0xFF2F78A8).withValues(alpha: 0.0),
      ],
    ).createShader(Rect.fromLTWH(0, paddingTop, w, usableH));
    canvas.drawPath(violetAreaPath, Paint()..shader = violetShader);

    // 2. Draw Main Spline / Line Strokes
    final greenStrokePaint = Paint()
      ..color = const Color(0xFF2FA37E)
      ..strokeWidth = 2.8
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    canvas.drawPath(greenPath, greenStrokePaint);

    final violetStrokePaint = Paint()
      ..color = const Color(0xFF2F78A8)
      ..strokeWidth = 2.8
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    canvas.drawPath(violetPath, violetStrokePaint);

    // 3. Draw All Pointed Vertices / Nodes on both lines
    final greenNodePaint = Paint()
      ..color = const Color(0xFF2FA37E)
      ..style = PaintingStyle.fill;
    final violetNodePaint = Paint()
      ..color = const Color(0xFF2F78A8)
      ..style = PaintingStyle.fill;
    final whiteInnerPaint = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.fill;

    for (int i = 0; i < greenPoints.length; i++) {
      // Received vertex
      canvas.drawCircle(greenPoints[i], 4.5, greenNodePaint);
      canvas.drawCircle(greenPoints[i], 2.2, whiteInnerPaint);

      // Sent vertex
      canvas.drawCircle(violetPoints[i], 4.5, violetNodePaint);
      canvas.drawCircle(violetPoints[i], 2.2, whiteInnerPaint);
    }

    // 4. Calculate Current Animated Scrub Position
    final clampedScrub = scrubFraction.clamp(0.0, (n - 1).toDouble());
    final scrubX = paddingX + (clampedScrub * xStep);

    // Interpolate Y positions at the exact scrub position
    final scrubYGreen = _interpolateY(greenPoints, clampedScrub);
    final scrubYViolet = _interpolateY(violetPoints, clampedScrub);

    // Interpolate Value amounts at scrub position
    final scrubValGreen = _interpolateValue(dataset.map((d) => d.received).toList(), clampedScrub);
    final scrubValViolet = _interpolateValue(dataset.map((d) => d.sent).toList(), clampedScrub);

    // 5. Draw Vertical Guideline
    final dashPaint = Paint()
      ..color = const Color(0xFF5AA9D6).withValues(alpha: 0.40)
      ..strokeWidth = 1.2
      ..style = PaintingStyle.stroke;
    _drawDashedVerticalLine(canvas, scrubX, paddingTop - 12, h - 8, dashPaint);

    // 6. Draw Highlighted Active Scrub Rings
    // Active Green Ring
    canvas.drawCircle(Offset(scrubX, scrubYGreen), 8.0, Paint()..color = const Color(0xFF2FA37E).withValues(alpha: 0.25));
    canvas.drawCircle(Offset(scrubX, scrubYGreen), 5.5, greenNodePaint);
    canvas.drawCircle(Offset(scrubX, scrubYGreen), 3.0, whiteInnerPaint);

    // Active Violet Ring
    canvas.drawCircle(Offset(scrubX, scrubYViolet), 8.0, Paint()..color = const Color(0xFF2F78A8).withValues(alpha: 0.25));
    canvas.drawCircle(Offset(scrubX, scrubYViolet), 5.5, violetNodePaint);
    canvas.drawCircle(Offset(scrubX, scrubYViolet), 3.0, whiteInnerPaint);

    // 7. Draw Floating Callout Text for active scrub point (dynamic in both Monthly & Yearly modes)
    final badgeX = scrubX.clamp(paddingX + 22.0, w - paddingX - 22.0);

    // Smart vertical layout to prevent callout overlap when lines cross
    final bool greenIsHigher = scrubYGreen <= scrubYViolet;
    final double greenY = greenIsHigher ? (scrubYGreen - 18) : (scrubYGreen + 18);
    final double violetY = greenIsHigher ? (scrubYViolet + 18) : (scrubYViolet - 18);

    _drawCalloutText(
      canvas: canvas,
      center: Offset(badgeX, greenY),
      text: '+${_formatCompactK(scrubValGreen)}',
      color: const Color(0xFF2FA37E),
    );

    _drawCalloutText(
      canvas: canvas,
      center: Offset(badgeX, violetY),
      text: '-${_formatCompactK(scrubValViolet)}',
      color: const Color(0xFF1C6E5A),
    );
  }

  Path _buildPath(List<Offset> points) {
    final path = Path();
    if (points.isEmpty) return path;
    path.moveTo(points[0].dx, points[0].dy);

    for (int i = 0; i < points.length - 1; i++) {
      final p0 = i > 0 ? points[i - 1] : points[i];
      final p1 = points[i];
      final p2 = points[i + 1];
      final p3 = (i + 2 < points.length) ? points[i + 2] : p2;

      final cp1x = p1.dx + (p2.dx - p0.dx) / 6;
      final cp1y = p1.dy + (p2.dy - p0.dy) / 6;
      final cp2x = p2.dx - (p3.dx - p1.dx) / 6;
      final cp2y = p2.dy - (p3.dy - p1.dy) / 6;

      path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.dx, p2.dy);
    }

    return path;
  }

  double _interpolateY(List<Offset> points, double fraction) {
    if (points.isEmpty) return 0;
    if (points.length == 1) return points.first.dy;

    final lower = fraction.floor().clamp(0, points.length - 1);
    final upper = fraction.ceil().clamp(0, points.length - 1);
    if (lower == upper) return points[lower].dy;

    final t = fraction - lower;
    return points[lower].dy + (points[upper].dy - points[lower].dy) * t;
  }

  double _interpolateValue(List<double> vals, double fraction) {
    if (vals.isEmpty) return 0;
    if (vals.length == 1) return vals.first;

    final lower = fraction.floor().clamp(0, vals.length - 1);
    final upper = fraction.ceil().clamp(0, vals.length - 1);
    if (lower == upper) return vals[lower];

    final t = fraction - lower;
    return vals[lower] + (vals[upper] - vals[lower]) * t;
  }

  void _drawDashedVerticalLine(Canvas canvas, double x, double startY, double endY, Paint paint) {
    const dashHeight = 4.0;
    const dashSpace = 3.5;
    double currentY = startY;
    while (currentY < endY) {
      canvas.drawLine(
        Offset(x, currentY),
        Offset(x, math.min(currentY + dashHeight, endY)),
        paint,
      );
      currentY += dashHeight + dashSpace;
    }
  }

  void _drawCalloutText({
    required Canvas canvas,
    required Offset center,
    required String text,
    required Color color,
  }) {
    final textStyle = const TextStyle(
      color: Colors.white,
      fontSize: 10.5,
      fontWeight: FontWeight.w800,
      letterSpacing: 0.2,
    );
    final textSpan = TextSpan(text: text, style: textStyle);
    final textPainter = TextPainter(
      text: textSpan,
      textDirection: TextDirection.ltr,
    )..layout();

    final pillWidth = textPainter.width + 14;
    final pillHeight = textPainter.height + 8;
    final pillRect = RRect.fromRectAndRadius(
      Rect.fromCenter(center: center, width: pillWidth, height: pillHeight),
      const Radius.circular(8),
    );

    // Draw pill soft ambient shadow & background
    canvas.drawRRect(
      pillRect.shift(const Offset(0, 2)),
      Paint()
        ..color = color.withValues(alpha: 0.28)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 3.5),
    );

    canvas.drawRRect(
      pillRect,
      Paint()
        ..color = color
        ..style = PaintingStyle.fill,
    );

    final textOffset = Offset(
      center.dx - (textPainter.width / 2),
      center.dy - (textPainter.height / 2),
    );
    textPainter.paint(canvas, textOffset);
  }

  String _formatCompactK(double val) {
    if (val >= 1000) {
      return '${(val / 1000).toStringAsFixed(1)}k';
    }
    return val.toStringAsFixed(0);
  }

  @override
  bool shouldRepaint(covariant _PointedTransferFlowPainter oldDelegate) {
    return oldDelegate.scrubFraction != scrubFraction ||
        oldDelegate.dataset != dataset ||
        oldDelegate.isMonthly != isMonthly;
  }
}
