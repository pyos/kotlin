// FOREIGN_ANNOTATIONS
import javax.annotation.*;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.annotation.meta.TypeQualifierNickname;
import javax.annotation.meta.When;

@Documented
@TypeQualifierNickname
@Nonnull(when = When.NEVER)
@Retention(RetentionPolicy.RUNTIME)
@interface MyNullable {

}

public class NonNullNever {
    @Nonnull(when = When.NEVER) public String field = null;

    @MyNullable
    public String foo(@Nonnull(when = When.NEVER) String x, @MyNullable CharSequence y) {
        return "";
    }
}
