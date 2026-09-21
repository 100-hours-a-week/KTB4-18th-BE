package com.muse.meomuneum.feature.auth.login.token;

import com.muse.meomuneum.feature.auth.login.user.UserAccount;

public interface TokenIssuer {

    IssuedTokens issue(UserAccount user);
}
