/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.ldap.authentication;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.junit.Test;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.ldap.authentication.PasswordComparisonAuthenticator;


/**
 *
 * @author Luke Taylor
 */
public class PasswordComparisonAuthenticatorMockTests {
    Mockery jmock = new JUnit4Mockery();

    //~ Methods ========================================================================================================

    @Test
    public void ldapCompareOperationIsUsedWhenPasswordIsNotRetrieved() throws Exception {
        final DirContext dirCtx = jmock.mock(DirContext.class);
        final BaseLdapPathContextSource source = jmock.mock(BaseLdapPathContextSource.class);
        final BasicAttributes attrs = new BasicAttributes();
        attrs.put(new BasicAttribute("uid", "bob"));

        PasswordComparisonAuthenticator authenticator = new PasswordComparisonAuthenticator(source);

        authenticator.setUserDnPatterns(new String[] {"cn={0},ou=people"});

        // Get the mock to return an empty attribute set
        jmock.checking(new Expectations() {{
            allowing(source).getReadOnlyContext(); will(returnValue(dirCtx));
            oneOf(dirCtx).getAttributes(with(equal("cn=Bob,ou=people")), with(aNull(String[].class))); will(returnValue(attrs));
            oneOf(dirCtx).getNameInNamespace(); will(returnValue("dc=springframework,dc=org"));
        }});

        // Setup a single return value (i.e. success)
        final Attributes searchResults = new BasicAttributes("", null);

        jmock.checking(new Expectations() {{
            oneOf(dirCtx).search(with(equal("cn=Bob,ou=people")),
                            with(equal("(userPassword={0})")),
                            with(aNonNull(Object[].class)),
                            with(aNonNull(SearchControls.class)));
            will(returnValue(searchResults.getAll()));
            atLeast(1).of(dirCtx).close();
        }});

        authenticator.authenticate(new UsernamePasswordAuthenticationToken("Bob","bobspassword"));

        jmock.assertIsSatisfied();
    }
}
