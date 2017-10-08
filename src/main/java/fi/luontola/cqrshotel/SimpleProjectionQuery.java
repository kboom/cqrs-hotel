// Copyright © 2016-2017 Esko Luontola
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package fi.luontola.cqrshotel;

import fi.luontola.cqrshotel.framework.Projection;
import fi.luontola.cqrshotel.framework.Query;
import fi.luontola.cqrshotel.util.Struct;

import java.util.function.Function;

public class SimpleProjectionQuery<P extends Projection> extends Struct implements Query {

    public final P projection;
    public final Function<P, Object> method;

    public SimpleProjectionQuery(P projection, Function<P, Object> method) {
        this.projection = projection;
        this.method = method;
    }
}
