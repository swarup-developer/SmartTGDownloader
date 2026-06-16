class Account {
  final String id;
  final String phoneNumber;
  final String sessionName;
  final bool isLoggedIn;
  final int apiId;
  final String apiHash;

  Account({
    required this.id,
    required this.phoneNumber,
    required this.sessionName,
    this.isLoggedIn = false,
    required this.apiId,
    required this.apiHash,
  });

  Account copyWith({
    String? id,
    String? phoneNumber,
    String? sessionName,
    bool? isLoggedIn,
    int? apiId,
    String? apiHash,
  }) {
    return Account(
      id: id ?? this.id,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      sessionName: sessionName ?? this.sessionName,
      isLoggedIn: isLoggedIn ?? this.isLoggedIn,
      apiId: apiId ?? this.apiId,
      apiHash: apiHash ?? this.apiHash,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'phoneNumber': phoneNumber,
      'sessionName': sessionName,
      'isLoggedIn': isLoggedIn ? 1 : 0,
      'apiId': apiId,
      'apiHash': apiHash,
    };
  }

  factory Account.fromMap(Map<String, dynamic> map) {
    return Account(
      id: map['id'] as String,
      phoneNumber: map['phoneNumber'] as String,
      sessionName: map['sessionName'] as String,
      isLoggedIn: (map['isLoggedIn'] as int? ?? 0) == 1,
      apiId: map['apiId'] as int,
      apiHash: map['apiHash'] as String,
    );
  }
}
