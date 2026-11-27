package com.zenread.data.translate

import com.zenread.data.translate.service.DeepLTranslator
import com.zenread.data.translate.service.GoogleCloudTranslator
import com.zenread.data.translate.service.GoogleFreeTranslator
import com.zenread.data.translate.service.MicrosoftTranslator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/** Built-in translators. Other modules add theirs the same way (see [TranslatorRegistry]). */
@Module
@InstallIn(SingletonComponent::class)
abstract class TranslateModule {
    @Multibinds
    abstract fun translators(): Set<Translator>

    @Binds @IntoSet
    abstract fun googleFree(impl: GoogleFreeTranslator): Translator

    @Binds @IntoSet
    abstract fun microsoft(impl: MicrosoftTranslator): Translator

    @Binds @IntoSet
    abstract fun deepL(impl: DeepLTranslator): Translator

    @Binds @IntoSet
    abstract fun googleCloud(impl: GoogleCloudTranslator): Translator
}
