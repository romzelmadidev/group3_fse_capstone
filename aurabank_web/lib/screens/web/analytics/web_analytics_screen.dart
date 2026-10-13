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
  final String rangeLabel;
  final String dateSubtitle;
  final double received;
  final double sent;
  final int inTransfers;
  final int outTransfers;
  final List<AnalyticsTxItem> transactions;

  const FlowPointData({
    required this.label,
    required this.shortLabel,
    this.rangeLabel = '',
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
  final DateTime postedAt;
  final double amount;
  final bool isReceived;
  final String status;

  const AnalyticsTxItem({
    required this.name,
    required this.initial,
    required this.avatarBg,
    required this.time,
    required this.postedAt,
    required this.amount,
    required this.isReceived,
    this.status = 'COMPLETED',
  });
}

class _MonthWeekSpan {
  final DateTime start;
  final DateTime end;

  const _MonthWeekSpan(this.start, this.end);
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

  // Selected indices. Null week means the whole month is in view.
  int _selectedMonthOfYear = 9; // Defaults to October 2026 (Index 9 in Jan..Dec)
  int? _selectedWeekIndex;
  int? _dragWeekIndex;
  int _selectedMonthIndex = 0;
  late AnimationController _animController;
  late Animation<double> _scrubAnimation;
  double _currentScrubFraction = 3.0; // Current floating position in index space

  // Catalog of all 12 Months in 2026 with weekly data
  late List<MonthFlowData> _monthsCatalog;

  late List<FlowPointData> _yearlyMonths2026;
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

      return MonthFlowData(
        monthName: '$mName 2026',
        shortName: sName,
        monthIndex: mIdx,
        weeks: _composeWeeks(mIdx),
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

  static const List<String> _shortMonthNames = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];

  static const List<String> _weekdayNames = [
    'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday',
  ];

  List<_MonthWeekSpan> _weekSpans(int year, int month) {
    final spans = <_MonthWeekSpan>[];
    var cursor = DateTime(year, month, 1);
    final monthEnd = DateTime(year, month + 1, 0);
    while (!cursor.isAfter(monthEnd)) {
      final daysUntilSunday = DateTime.sunday - cursor.weekday;
      var end = DateTime(cursor.year, cursor.month, cursor.day + daysUntilSunday);
      if (end.isAfter(monthEnd)) end = monthEnd;
      spans.add(_MonthWeekSpan(cursor, end));
      cursor = DateTime(end.year, end.month, end.day + 1);
    }
    return spans;
  }

  String _spanRangeLabel(_MonthWeekSpan span) {
    final startLabel = _shortMonthNames[span.start.month - 1];
    final endLabel = _shortMonthNames[span.end.month - 1];
    if (span.start.month == span.end.month) {
      return '$startLabel ${span.start.day}–${span.end.day}';
    }
    return '$startLabel ${span.start.day}–$endLabel ${span.end.day}';
  }

  List<FlowPointData> _composeWeeks(
    int monthIndex, {
    List<double>? received,
    List<double>? sent,
    List<int>? inCounts,
    List<int>? outCounts,
    List<List<AnalyticsTxItem>>? txns,
  }) {
    final spans = _weekSpans(2026, monthIndex + 1);
    return List.generate(spans.length, (w) {
      final range = _spanRangeLabel(spans[w]);
      return FlowPointData(
        label: 'Week ${w + 1}',
        shortLabel: 'W${w + 1}',
        rangeLabel: range,
        dateSubtitle: '$range, 2026',
        received: (received != null && w < received.length) ? received[w] : 0.0,
        sent: (sent != null && w < sent.length) ? sent[w] : 0.0,
        inTransfers: (inCounts != null && w < inCounts.length) ? inCounts[w] : 0,
        outTransfers: (outCounts != null && w < outCounts.length) ? outCounts[w] : 0,
        transactions: (txns != null && w < txns.length) ? txns[w] : const [],
      );
    });
  }

  int _weekIndexForDay(DateTime day, List<_MonthWeekSpan> spans) {
    final date = DateTime(day.year, day.month, day.day);
    for (var i = 0; i < spans.length; i++) {
      final start = DateTime(spans[i].start.year, spans[i].start.month, spans[i].start.day);
      final end = DateTime(spans[i].end.year, spans[i].end.month, spans[i].end.day);
      if (!date.isBefore(start) && !date.isAfter(end)) return i;
    }
    return 0;
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

    final spansByMonth = List.generate(12, (m) => _weekSpans(2026, m + 1));
    final weekReceived = spansByMonth.map((spans) => List<double>.filled(spans.length, 0.0)).toList();
    final weekSent = spansByMonth.map((spans) => List<double>.filled(spans.length, 0.0)).toList();
    final weekInCount = spansByMonth.map((spans) => List<int>.filled(spans.length, 0)).toList();
    final weekOutCount = spansByMonth.map((spans) => List<int>.filled(spans.length, 0)).toList();
    final weekTxns = spansByMonth.map((spans) => List.generate(spans.length, (_) => <AnalyticsTxItem>[])).toList();

    for (final item in rawList) {
      if (item is! Map) continue;
      final createdAtStr = (item['created_at'] ?? item['createdAt'])?.toString();
      if (createdAtStr == null) continue;

      DateTime? parsed = DateTime.tryParse(createdAtStr);
      if (parsed == null) continue;
      final dt = parsed.toLocal();

      if (dt.year != 2026) continue;
      final mIdx = dt.month - 1;
      if (mIdx < 0 || mIdx > 11) continue;

      final wIdx = _weekIndexForDay(dt, spansByMonth[mIdx]);

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
        postedAt: dt,
        amount: amount,
        isReceived: isIncoming,
        status: isIncoming ? 'RECEIVED' : 'SETTLED',
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

      return MonthFlowData(
        monthName: '$mName 2026',
        shortName: sName,
        monthIndex: mIdx,
        weeks: _composeWeeks(
          mIdx,
          received: weekReceived[mIdx],
          sent: weekSent[mIdx],
          inCounts: weekInCount[mIdx],
          outCounts: weekOutCount[mIdx],
          txns: weekTxns[mIdx],
        ),
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

      for (int w = 0; w < weekReceived[mIdx].length; w++) {
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
      _dragWeekIndex = null;
      _selectedWeekIndex = _selectedWeekIndex == index ? null : index;
    });
  }

  void _setMonth(int index) {
    if (index < 0 || index > 11 || index == _selectedMonthOfYear) return;
    setState(() {
      _selectedMonthOfYear = index;
      _selectedWeekIndex = null;
      _dragWeekIndex = null;
    });
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
      _dragWeekIndex = null;
      if (!monthly) {
        final maxLen = _currentYearlyFilteredMonths.length;
        _selectedMonthIndex = _selectedMonthIndex.clamp(0, maxLen - 1);
        _currentScrubFraction = _selectedMonthIndex.toDouble();
      }
    });
    if (!monthly) {
      _animateToTarget(_selectedMonthIndex.toDouble());
    }
  }

  List<FlowPointData> get _currentMonthlyWeeks =>
      _monthsCatalog[_selectedMonthOfYear].weeks;

  int? get _focusedWeekIndex {
    final index = _selectedWeekIndex;
    if (index == null || index < 0 || index >= _currentMonthlyWeeks.length) return null;
    return index;
  }

  List<FlowPointData> get _scopedMonthlyPoints {
    final weeks = _currentMonthlyWeeks;
    final index = _focusedWeekIndex;
    if (index == null) return weeks;
    return [weeks[index]];
  }

  String get _monthlyPeriodLabel {
    final index = _focusedWeekIndex;
    if (index == null) return _monthsCatalog[_selectedMonthOfYear].monthName;
    return _currentMonthlyWeeks[index].dateSubtitle;
  }

  List<FlowPointData> get _currentFullYearMonths => _yearlyMonths2026;

  List<FlowPointData> get _currentYearlyFilteredMonths => _yearlyMonths2026;

  List<FlowPointData> get _currentDataset =>
      _isMonthly ? _currentMonthlyWeeks : _currentYearlyFilteredMonths;



  // Grand totals
  double get _totalSentMonth {
    double sum = 0;
    for (final w in _scopedMonthlyPoints) {
      sum += w.sent;
    }
    return sum;
  }

  double get _totalReceivedMonth {
    double sum = 0;
    for (final w in _scopedMonthlyPoints) {
      sum += w.received;
    }
    return sum;
  }

  int get _transfersOutMonth {
    int sum = 0;
    for (final w in _scopedMonthlyPoints) {
      sum += w.outTransfers;
    }
    return sum;
  }

  int get _transfersInMonth {
    int sum = 0;
    for (final w in _scopedMonthlyPoints) {
      sum += w.inTransfers;
    }
    return sum;
  }

  double get _totalSentYear {
    var sum = 0.0;
    for (final month in _yearlyMonths2026) {
      sum += month.sent;
    }
    return sum;
  }

  double get _totalReceivedYear {
    var sum = 0.0;
    for (final month in _yearlyMonths2026) {
      sum += month.received;
    }
    return sum;
  }

  int get _transfersOutYear {
    var sum = 0;
    for (final month in _yearlyMonths2026) {
      sum += month.outTransfers;
    }
    return sum;
  }

  int get _transfersInYear {
    var sum = 0;
    for (final month in _yearlyMonths2026) {
      sum += month.inTransfers;
    }
    return sum;
  }

  String get _yearOutflowShareLabel {
    final total = _totalSentYear + _totalReceivedYear;
    if (total <= 0) return 'No volume';
    final pct = ((_totalSentYear / total) * 100).round();
    return '$pct% of volume';
  }

  String get _outflowShareLabel {
    final total = _totalSentMonth + _totalReceivedMonth;
    if (total <= 0) return 'No volume';
    final pct = ((_totalSentMonth / total) * 100).round();
    return '$pct% of volume';
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

              if (_isMonthly) ...[
                const SizedBox(height: 16),
                _buildMonthStepper(),
              ],

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

  Widget _buildMonthStepper() {
    final month = _monthsCatalog[_selectedMonthOfYear];
    return Row(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 2),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(30),
            border: Border.all(color: const Color(0xFFEAECEE)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              _monthStepButton(
                icon: Icons.chevron_left_rounded,
                tooltip: 'Previous month',
                onPressed: _selectedMonthOfYear > 0 ? () => _setMonth(_selectedMonthOfYear - 1) : null,
              ),
              PopupMenuButton<int>(
                tooltip: 'Choose month',
                initialValue: _selectedMonthOfYear,
                onSelected: _setMonth,
                offset: const Offset(0, 12),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                itemBuilder: (context) => List.generate(12, (index) {
                  final selected = index == _selectedMonthOfYear;
                  return PopupMenuItem<int>(
                    value: index,
                    height: 40,
                    child: Row(
                      children: [
                        SizedBox(
                          width: 22,
                          child: selected
                              ? const Icon(Icons.check_rounded, size: 16, color: Color(0xFF10171C))
                              : const SizedBox.shrink(),
                        ),
                        Text(
                          _monthsCatalog[index].monthName,
                          style: TextStyle(
                            fontSize: 13.5,
                            fontWeight: selected ? FontWeight.w800 : FontWeight.w500,
                            color: const Color(0xFF10171C),
                          ),
                        ),
                      ],
                    ),
                  );
                }),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        month.monthName,
                        style: const TextStyle(
                          fontSize: 13.5,
                          fontWeight: FontWeight.w800,
                          color: Color(0xFF10171C),
                          letterSpacing: -0.2,
                        ),
                      ),
                      const SizedBox(width: 2),
                      const Icon(Icons.keyboard_arrow_down_rounded, size: 18, color: Color(0xFF47525C)),
                    ],
                  ),
                ),
              ),
              _monthStepButton(
                icon: Icons.chevron_right_rounded,
                tooltip: 'Next month',
                onPressed: _selectedMonthOfYear < 11 ? () => _setMonth(_selectedMonthOfYear + 1) : null,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _monthStepButton({
    required IconData icon,
    required String tooltip,
    required VoidCallback? onPressed,
  }) {
    return IconButton(
      tooltip: tooltip,
      onPressed: onPressed,
      visualDensity: VisualDensity.compact,
      icon: Icon(
        icon,
        size: 20,
        color: onPressed == null ? const Color(0xFFD5DADF) : const Color(0xFF10171C),
      ),
    );
  }

  Widget _kpiHeading(String title, {required Color titleColor, required bool onDark}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: TextStyle(
            fontSize: 12.5,
            fontWeight: FontWeight.w600,
            color: titleColor,
          ),
        ),
        if (_isMonthly) ...[
          const SizedBox(height: 2),
          Text(
            _monthlyPeriodLabel,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: onDark ? const Color(0xFFC4B5FD) : const Color(0xFF7D8892),
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildMonthChangeBadge() {
    if (!_isMonthly || _focusedWeekIndex != null || _selectedMonthOfYear == 0) {
      return const SizedBox.shrink();
    }
    final previous = _monthsCatalog[_selectedMonthOfYear - 1];
    var previousSent = 0.0;
    var previousReceived = 0.0;
    for (final week in previous.weeks) {
      previousSent += week.sent;
      previousReceived += week.received;
    }
    if (previousSent == 0 && previousReceived == 0) return const SizedBox.shrink();
    final previousNet = previousReceived - previousSent;
    if (previousNet == 0) return const SizedBox.shrink();
    final currentNet = _totalReceivedMonth - _totalSentMonth;
    final pct = ((currentNet - previousNet) / previousNet.abs()) * 100;
    final up = pct >= 0;
    final tone = up ? const Color(0xFF15803D) : const Color(0xFF9B3A3A);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: up ? const Color(0xFFE4F5EE) : const Color(0xFFF8E8E8),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(up ? Icons.trending_up_rounded : Icons.trending_down_rounded, size: 13, color: tone),
          const SizedBox(width: 3),
          Text(
            '${up ? '+' : ''}${pct.toStringAsFixed(1)}% vs ${previous.shortName}',
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w800,
              color: tone,
            ),
          ),
        ],
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
    final sentAmount = _isMonthly ? _totalSentMonth : _totalSentYear;
    final sentCount = _isMonthly ? '$_transfersOutMonth Transfers Out' : '$_transfersOutYear Transfers Out';

    final receivedTitle = _isMonthly ? 'Total Received' : 'Total Received (2026)';
    final receivedAmount = _isMonthly ? _totalReceivedMonth : _totalReceivedYear;
    final receivedCount = _isMonthly ? '$_transfersInMonth Transfers In' : '$_transfersInYear Transfers In';

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
                    Flexible(
                      child: _kpiHeading(
                        sentTitle,
                        titleColor: const Color(0xFFD8B4FE),
                        onDark: true,
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
                    Flexible(
                      child: _kpiHeading(
                        receivedTitle,
                        titleColor: const Color(0xFF1C6E5A),
                        onDark: false,
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
                    Flexible(
                      child: _kpiHeading(
                        'Net Volume Difference',
                        titleColor: const Color(0xFF47525C),
                        onDark: false,
                      ),
                    ),
                    const SizedBox(width: 8),
                    _buildMonthChangeBadge(),
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
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'Expenditure Ratio',
                      style: TextStyle(
                        fontSize: 12,
                        color: Color(0xFF7D8892),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    Text(
                      _isMonthly ? _outflowShareLabel : _yearOutflowShareLabel,
                      style: const TextStyle(
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
                children: [
                  const Text(
                    'Transfer Flow',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      color: Color(0xFF10171C),
                      letterSpacing: -0.4,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    _isMonthly
                        ? (_focusedWeekIndex == null
                            ? 'Each week of ${_monthsCatalog[_selectedMonthOfYear].monthName}. Select a week to focus.'
                            : 'Showing $_monthlyPeriodLabel. The chart still compares every week.')
                        : 'Smooth comparison of outgoing versus incoming capital',
                    style: const TextStyle(
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
                onTapUp: (details) {
                  final targetIndex = _indexForChartX(details.localPosition.dx, chartWidth, dataset.length);
                  if (_isMonthly) {
                    _onSelectWeek(targetIndex);
                  } else {
                    _onSelectMonth(targetIndex);
                  }
                },
                onHorizontalDragUpdate: (details) {
                  if (!_isMonthly) {
                    _handleScrubGesture(details.localPosition.dx, chartWidth, dataset.length);
                    return;
                  }
                  final targetIndex = _indexForChartX(details.localPosition.dx, chartWidth, dataset.length);
                  if (_dragWeekIndex == targetIndex) return;
                  setState(() => _dragWeekIndex = targetIndex);
                },
                onHorizontalDragEnd: (details) {
                  if (!_isMonthly) {
                    final targetIndex = _currentScrubFraction.round().clamp(0, dataset.length - 1);
                    _onSelectMonth(targetIndex);
                    return;
                  }
                  final targetIndex = _dragWeekIndex;
                  setState(() {
                    _dragWeekIndex = null;
                    if (targetIndex != null) _selectedWeekIndex = targetIndex;
                  });
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
                        activeIndex: _isMonthly ? (_dragWeekIndex ?? _focusedWeekIndex) : null,
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
            clipBehavior: Clip.antiAlias,
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
      _selectedMonthIndex = indexFraction.round().clamp(0, count - 1);
    });
  }

  int _indexForChartX(double localX, double totalWidth, int count) {
    const double paddingX = 24.0;
    final usableWidth = totalWidth - (2 * paddingX);
    if (count <= 1 || usableWidth <= 0) return 0;
    final normalized = ((localX - paddingX) / usableWidth).clamp(0.0, 1.0);
    return (normalized * (count - 1)).round().clamp(0, count - 1);
  }

  Widget _buildWeekClickablePills() {
    final weeks = _currentMonthlyWeeks;
    final highlighted = _dragWeekIndex ?? _focusedWeekIndex;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          _scopePill(
            title: 'All month',
            subtitle: _monthsCatalog[_selectedMonthOfYear].shortName,
            selected: highlighted == null,
            onTap: () {
              setState(() {
                _selectedWeekIndex = null;
                _dragWeekIndex = null;
              });
            },
          ),
          for (var index = 0; index < weeks.length; index++) ...[
            const SizedBox(width: 4),
            _scopePill(
              title: 'Week ${index + 1}',
              subtitle: weeks[index].rangeLabel,
              selected: highlighted == index,
              onTap: () => _onSelectWeek(index),
            ),
          ],
        ],
      ),
    );
  }

  Widget _scopePill({
    required String title,
    required String subtitle,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 12.0, vertical: 6.0),
          decoration: BoxDecoration(
            color: selected ? Colors.white : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
            border: selected ? Border.all(color: const Color(0xFFEAECEE)) : null,
            boxShadow: selected
                ? [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.05),
                      blurRadius: 4,
                      offset: const Offset(0, 1),
                    ),
                  ]
                : null,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                title,
                style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                  color: selected ? const Color(0xFF10171C) : const Color(0xFF7D8892),
                ),
              ),
              Text(
                subtitle,
                style: TextStyle(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w500,
                  color: selected ? const Color(0xFF47525C) : const Color(0xFF98A2AB),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildMonthClickablePills() {
    final months = _currentYearlyFilteredMonths;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (var index = 0; index < months.length; index++) ...[
            if (index > 0) const SizedBox(width: 4),
            _scopePill(
              title: months[index].shortLabel,
              subtitle: months[index].label,
              selected: _selectedMonthIndex == index,
              onTap: () => _onSelectMonth(index),
            ),
          ],
        ],
      ),
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
                children: [
                  const Text(
                    'Settlement History Ledger',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w800,
                      color: Color(0xFF10171C),
                      letterSpacing: -0.3,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    _focusedWeekIndex == null
                        ? 'Transfers settled & cleared in ${_monthsCatalog[_selectedMonthOfYear].monthName}'
                        : 'Transfers settled & cleared in $_monthlyPeriodLabel',
                    style: const TextStyle(
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
          _buildMonthlyLedgerBody(),
        ],
      ),
    );
  }

  Widget _buildMonthlyLedgerBody() {
    final transactions = <AnalyticsTxItem>[];
    for (final point in _scopedMonthlyPoints) {
      transactions.addAll(point.transactions);
    }
    transactions.sort((a, b) => b.postedAt.compareTo(a.postedAt));

    if (transactions.isEmpty) {
      final period = _focusedWeekIndex == null
          ? _monthsCatalog[_selectedMonthOfYear].monthName
          : _monthlyPeriodLabel;
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 28),
        child: Center(
          child: Text(
            'No transfers in $period.',
            style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: Color(0xFF7D8892),
            ),
          ),
        ),
      );
    }

    final groups = <DateTime, List<AnalyticsTxItem>>{};
    for (final tx in transactions) {
      final day = DateTime(tx.postedAt.year, tx.postedAt.month, tx.postedAt.day);
      groups.putIfAbsent(day, () => <AnalyticsTxItem>[]).add(tx);
    }
    final days = groups.keys.toList()..sort((a, b) => b.compareTo(a));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < days.length; i++) ...[
          if (i > 0) const SizedBox(height: 18),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F7F7),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              '${_weekdayNames[days[i].weekday - 1]}, ${_monthsCatalog[days[i].month - 1].monthName.split(' ').first} ${days[i].day}, ${days[i].year}',
              style: const TextStyle(
                fontSize: 11,
                color: Color(0xFF47525C),
                fontWeight: FontWeight.w700,
                letterSpacing: 0.2,
              ),
            ),
          ),
          const SizedBox(height: 10),
          for (var j = 0; j < groups[days[i]]!.length; j++) ...[
            if (j > 0)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: Divider(height: 1, color: Color(0xFFF1F3F4)),
              ),
            _ledgerRow(groups[days[i]]![j]),
          ],
        ],
      ],
    );
  }

  Widget _ledgerRow(AnalyticsTxItem tx) {
    final positive = tx.isReceived;
    return _buildTransactionItem(
      avatarBg: tx.avatarBg,
      initial: tx.initial,
      name: tx.name,
      category: positive ? 'Incoming transfer' : 'Outgoing transfer',
      time: tx.time,
      amount: '${positive ? '+' : '-'} PHP ${_formatCurrency(tx.amount)}',
      isPositive: positive,
      status: tx.status,
      statusColor: const Color(0xFF17805F),
      statusBg: const Color(0xFFE4F5EE),
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
              const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
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
                    'Posted transfers in 2026',
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
          ..._buildYearlyQuarterGroups(),
        ],
      ),
    );
  }

  List<Widget> _buildYearlyQuarterGroups() {
    const quarterNames = ['Quarter 1 (Q1 2026)', 'Quarter 2 (Q2 2026)', 'Quarter 3 (Q3 2026)', 'Quarter 4 (Q4 2026)'];
    const avatarColors = [
      Color(0xFF10171C),
      Color(0xFF1C6E5A),
      Color(0xFF2F78A8),
      Color(0xFF4A5568),
    ];
    final months = _yearlyMonths2026;
    final grouped = <int, List<int>>{};
    for (var index = 0; index < months.length; index++) {
      final month = months[index];
      if (month.sent == 0 && month.received == 0) continue;
      grouped.putIfAbsent(index ~/ 3, () => <int>[]).add(index);
    }
    if (grouped.isEmpty) {
      return const [
        Padding(
          padding: EdgeInsets.symmetric(vertical: 28),
          child: Center(
            child: Text(
              'No transfers in 2026.',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: Color(0xFF7D8892),
              ),
            ),
          ),
        ),
      ];
    }

    final widgets = <Widget>[];
    final quarterKeys = grouped.keys.toList()..sort();
    for (var q = 0; q < quarterKeys.length; q++) {
      final key = quarterKeys[q];
      if (q > 0) widgets.add(const SizedBox(height: 18));
      widgets.add(
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
            color: const Color(0xFFF7F7F7),
            borderRadius: BorderRadius.circular(6),
          ),
          child: Text(
            quarterNames[key],
            style: const TextStyle(
              fontSize: 11,
              color: Color(0xFF47525C),
              fontWeight: FontWeight.w700,
              letterSpacing: 0.2,
            ),
          ),
        ),
      );
      widgets.add(const SizedBox(height: 10));
      final indexes = grouped[key]!;
      for (var i = 0; i < indexes.length; i++) {
        if (i > 0) {
          widgets.add(
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Divider(height: 1, color: Color(0xFFF1F3F4)),
            ),
          );
        }
        final month = months[indexes[i]];
        final transfers = month.inTransfers + month.outTransfers;
        final net = month.received - month.sent;
        widgets.add(
          _buildQuarterRow(
            initial: month.shortLabel,
            monthTitle: '${month.label} 2026',
            transfersText: '$transfers ${transfers == 1 ? 'transfer' : 'transfers'} • ',
            greenAmount: '+ ${_formatCurrency(month.received)}',
            gross: 'PHP ${_formatCurrency(month.sent)}',
            net: '${net >= 0 ? '+' : '-'} PHP ${_formatCurrency(net.abs())} net',
            avatarBg: avatarColors[indexes[i] % avatarColors.length],
          ),
        );
      }
    }
    return widgets;
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
  final int? activeIndex;
  final bool isMonthly;

  _PointedTransferFlowPainter({
    required this.dataset,
    required this.scrubFraction,
    required this.activeIndex,
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

    // 4. Highlight the focused week. The whole-month view keeps every point equal.
    final bool showScrub = !isMonthly || activeIndex != null;
    if (!showScrub) return;

    final clampedScrub = isMonthly
        ? activeIndex!.toDouble().clamp(0.0, (n - 1).toDouble())
        : scrubFraction.clamp(0.0, (n - 1).toDouble());
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
        oldDelegate.activeIndex != activeIndex ||
        oldDelegate.dataset != dataset ||
        oldDelegate.isMonthly != isMonthly;
  }
}
